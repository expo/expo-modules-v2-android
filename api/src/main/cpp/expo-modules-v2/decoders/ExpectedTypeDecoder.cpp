#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2::decoders {
  namespace {
    struct Decoder {
      kolibri::binary::Reader& reader;
      size_t remaining;
      bool allowBufferedHead;

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
          // Every element takes at least one code, so a larger arity cannot be a valid payload.
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

        if (kind == CppType::SHARED_OBJECT) {
          // TODO(@lukmccall): remove check?
          if (usesBuffer) {
            throwInvalidArgument({"A shared object cannot carry kUsesBufferFlag"});
          }
          return ExpectedType::sharedObject(read(), nullable);
        }

        if (nullable && (
              kind == CppType::BOOLEAN || kind == CppType::INT || kind == CppType::LONG ||
              kind == CppType::FLOAT || kind == CppType::DOUBLE || kind == CppType::UNIT
            )) {
          throwInvalidArgument({"Scalar CppType cannot carry the nullable flag"});
        }

        if (usesBuffer) {
          requireBufferableLeaf(kind);
        }

        // We can assume that it's a LeafType
        return ExpectedType(static_cast<LeafType>(kind), nullable, usesBuffer);
      }

      static void requireBufferableLeaf(const CppType kind) {
        switch (kind) {
          case CppType::ANY:
          case CppType::STRING:
          case CppType::DOUBLE_ARRAY:
          case CppType::INT_ARRAY:
          case CppType::LONG_ARRAY:
          case CppType::FLOAT_ARRAY:
          case CppType::BOOLEAN_ARRAY:
          case CppType::BYTE_ARRAY:
          case CppType::BOX_INT:
          case CppType::BOX_BOOLEAN:
          case CppType::BOX_LONG:
          case CppType::BOX_FLOAT:
          case CppType::BOX_DOUBLE:
            return;
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
