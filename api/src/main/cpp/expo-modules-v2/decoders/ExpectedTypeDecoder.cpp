#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include <expo-modules-v2/unions/UnionSchema.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2::decoders {
  namespace {
    struct Decoder {
      kolibri::binary::Reader& reader;
      size_t remaining;
      bool allowBufferedHead;
      // The unions being decoded, outermost first: what a UNION_REF points back at.
      std::vector<const UnionSchema*> unions{};

      ExpectedType decode() {
        ExpectedType type = decodeImp(/* isHead */ true);
        return type;
      }

    private:
      ExpectedType decodeImp(bool isHead) {
        const int rawCode = read();
        const bool nullable = (rawCode & kNullableFlag) != 0;
        const bool usesBuffer = (rawCode & kUsesBufferFlag) != 0;
        // We can assume that the kind bits are correct
        const CppType kind = static_cast<CppType>(rawCode & ~kHeadFlagsMask);

        if (usesBuffer && !isHead) {
          throwInvalidArgument({
            "kUsesBufferFlag is head-position only; found it on a nested type code",
          });
        }
        if (usesBuffer && !allowBufferedHead) {
          throwInvalidArgument({
            "kUsesBufferFlag is not allowed in this context: record fields ride their "
            "record's transport",
          });
        }

        if (kind == CppType::LIST) {
          ExpectedType type = ExpectedType::list(decodeImp(false), nullable, usesBuffer);
          requireBufferSafeIfBuffered(type);
          return type;
        }

        if (kind == CppType::MAP) {
          ExpectedType type = ExpectedType::map(decodeImp(false), nullable, usesBuffer);
          requireBufferSafeIfBuffered(type);
          return type;
        }

        if (kind == CppType::RECORD) {
          ExpectedType type = ExpectedType::record(read(), nullable, usesBuffer);
          requireBufferSafeIfBuffered(type);
          return type;
        }

        if (kind == CppType::TUPLE) {
          const int arity = read();
          if (arity <= 0 || static_cast<size_t>(arity) > remaining) {
            throwInvalidArgument({"Invalid tuple arity ", std::to_string(arity)});
          }

          std::vector<ExpectedType> elements;
          elements.reserve(static_cast<size_t>(arity));
          for (int i = 0; i < arity; i++) {
            elements.push_back(decodeImp(false));
          }

          ExpectedType type = ExpectedType::tuple(std::move(elements), nullable, usesBuffer);
          requireBufferSafeIfBuffered(type);
          return type;
        }

        if (kind == CppType::UNION) {
          ExpectedType type = ExpectedType::unionOf(decodeUnion(), nullable, usesBuffer);
          requireBufferSafeIfBuffered(type);
          return type;
        }

        if (kind == CppType::UNION_REF) {
          // Only ever nested inside the union it points at, so it never carries the buffer flag:
          // a head-position ref has no union around it and fails the depth check.
          const int depth = read();
          if (depth < 0 || static_cast<size_t>(depth) >= unions.size()) {
            throwInvalidArgument({"Invalid union back-reference depth ", std::to_string(depth)});
          }
          return ExpectedType::unionRef(unions[unions.size() - 1 - static_cast<size_t>(depth)], nullable);
        }

        if (kind == CppType::SHARED_OBJECT) {
          // TODO(@lukmccall): remove check?
          if (usesBuffer) {
            throwInvalidArgument({"A shared object cannot carry kUsesBufferFlag"});
          }
          return ExpectedType::sharedObject(read(), nullable);
        }

        if (nullable && kind == CppType::UNIT) {
          throwInvalidArgument({"UNIT cannot carry the nullable flag"});
        }

        if (usesBuffer) {
          requireBufferableLeaf(kind, nullable);
        }

        // We can assume that it's a LeafType
        return ExpectedType(static_cast<LeafType>(kind), nullable, usesBuffer);
      }

      /**
       * `count, name, variant...`, where a variant is its type codes, or `UNIT, tag` for an
       * `object` variant. A variant that holds this union again carries a UNION_REF to it.
       */
      std::shared_ptr<const UnionSchema> decodeUnion() {
        const int count = read();
        if (count <= 0 || static_cast<size_t>(count) > remaining) {
          throwInvalidArgument({"Invalid union variant count ", std::to_string(count)});
        }

        auto schema = std::make_shared<UnionSchema>();
        schema->name = readString();
        schema->variants.reserve(static_cast<size_t>(count));

        unions.push_back(schema.get());
        for (int i = 0; i < count; i++) {
          ExpectedType type = decodeImp(false);
          if (type.kind() == CppType::UNIT) {
            std::string tag = readString();
            schema->variants.push_back(
              UnionVariantSpec{
                .kind = UnionVariantKind::LITERAL,
                .tag = std::move(tag),
                .type = std::move(type),
              }
            );
          } else {
            const UnionVariantKind variantKind = variantKindOf(type);
            schema->variants.push_back(
              UnionVariantSpec{
                .kind = variantKind,
                .tag = {},
                .type = std::move(type)
              }
            );
          }
        }
        unions.pop_back();

        // A record or list that refuses a value throws to pass it on; when nothing after it could
        // take the value, there is nobody to pass it to, and the check before converting is wasted.
        const auto alsoTakes = [](const UnionVariantSpec& variant, const UnionVariantKind later) {
          switch (later) {
            case UnionVariantKind::ANY_OBJECT:
            case UnionVariantKind::ANY:
              return true;
            case UnionVariantKind::OBJECT:
            case UnionVariantKind::BINARY:
            case UnionVariantKind::SHARED_OBJECT:
              return variant.kind != UnionVariantKind::ARRAY;
            case UnionVariantKind::ARRAY:
              return variant.kind == UnionVariantKind::ARRAY;
            default:
              return false;
          }
        };
        for (size_t at = 0; at < schema->variants.size(); at++) {
          UnionVariantSpec& variant = schema->variants[at];
          switch (variant.kind) {
            case UnionVariantKind::ARRAY:
            case UnionVariantKind::OBJECT:
            case UnionVariantKind::BINARY:
            case UnionVariantKind::SHARED_OBJECT:
              break;
            default:
              continue;
          }
          for (size_t later = at + 1; later < schema->variants.size(); later++) {
            if (alsoTakes(variant, schema->variants[later].kind)) {
              variant.checkBeforeTrying = true;
              break;
            }
          }
        }
        return schema;
      }

      /**
       * The kind of JavaScript value a variant of [type] takes. MUST stay in sync with `jsKindOf` in
       * the compiler plugin's `UnionCheckers`, which rejects every type this rejects.
       */
      static UnionVariantKind variantKindOf(const ExpectedType& type) {
        switch (type.kind()) {
          case CppType::BOOLEAN:
            return UnionVariantKind::BOOLEAN;
          case CppType::INT:
          case CppType::LONG:
          case CppType::FLOAT:
          case CppType::DOUBLE:
            return UnionVariantKind::NUMBER;
          case CppType::STRING:
            return UnionVariantKind::STRING;
          case CppType::LIST:
          case CppType::DOUBLE_ARRAY:
          case CppType::INT_ARRAY:
          case CppType::LONG_ARRAY:
          case CppType::FLOAT_ARRAY:
          case CppType::BOOLEAN_ARRAY:
            return UnionVariantKind::ARRAY;
          case CppType::BYTE_ARRAY:
            return UnionVariantKind::BINARY;
          case CppType::MAP:
          case CppType::RECORD:
            return UnionVariantKind::OBJECT;
          case CppType::SHARED_OBJECT:
            return UnionVariantKind::SHARED_OBJECT;
          case CppType::JS_OBJECT:
            return UnionVariantKind::ANY_OBJECT;
          case CppType::ANY:
          case CppType::JS_VALUE:
            return UnionVariantKind::ANY;
          default:
            throwInvalidArgument({"A union variant of this type has no JavaScript kind"});
        }
      }

      /** `length, byte...`: a UTF-8 string, one byte per code. */
      std::string readString() {
        const int length = read();
        if (length < 0 || static_cast<size_t>(length) > remaining) {
          throwInvalidArgument({"Invalid string length ", std::to_string(length)});
        }

        std::string result(static_cast<size_t>(length), '\0');
        for (char& byte: result) {
          byte = static_cast<char>(read());
        }
        return result;
      }

      static void requireBufferableLeaf(const CppType kind, const bool nullable) {
        switch (kind) {
          case CppType::ANY:
          case CppType::STRING:
          case CppType::DOUBLE_ARRAY:
          case CppType::INT_ARRAY:
          case CppType::LONG_ARRAY:
          case CppType::FLOAT_ARRAY:
          case CppType::BOOLEAN_ARRAY:
          case CppType::BYTE_ARRAY:
            return;
          // A scalar rides the buffer only boxed: when it is nullable.
          case CppType::BOOLEAN:
          case CppType::INT:
          case CppType::LONG:
          case CppType::FLOAT:
          case CppType::DOUBLE:
            if (nullable) {
              return;
            }
            [[fallthrough]];
          default:
            throwInvalidArgument({"This CppType cannot carry kUsesBufferFlag"});
        }
      }

      static void requireBufferSafeIfBuffered(const ExpectedType& type) {
        if (type.usesBuffer() && !type.bufferSafe()) {
          throwInvalidArgument({
            "kUsesBufferFlag on a type that is not buffer-safe (it contains a JSI handle)",
          });
        }
      }

      int read() {
        if (remaining == 0) {
          throwInvalidArgument({"Invalid payload"});
        }

        remaining--;
        return reader.read<int32_t>();
      }
    };
  }

  ExpectedType decodeExpectedType(kolibri::binary::Reader& reader, bool allowBufferedHead) {
    Decoder decoder{
      .reader = reader,
      .remaining = reader.readCount(),
      .allowBufferedHead = allowBufferedHead
    };
    return decoder.decode();
  }
}
