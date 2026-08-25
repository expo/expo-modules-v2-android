#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

#include <stdexcept>
#include <string>

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
          throw std::invalid_argument(
            "kUsesBufferFlag is head-position only; found it on a nested type code"
          );
        }
        if (usesBuffer && !allowBufferedHead) {
          throw std::invalid_argument(
            "kUsesBufferFlag is not allowed in this context: record fields ride their "
            "record's transport"
          );
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

        if (nullable && (
              kind == CppType::BOOLEAN || kind == CppType::INT || kind == CppType::LONG ||
              kind == CppType::FLOAT || kind == CppType::DOUBLE || kind == CppType::UNIT
            )) {
          throw std::invalid_argument("Scalar CppType cannot carry the nullable flag");
        }

        if (usesBuffer) {
          requireBufferableLeaf(kind);
        }

        // We can assume that it's a LeafType
        return ExpectedType(static_cast<LeafType>(kind), nullable, usesBuffer);
      }

      // Leaves the binary codec can carry: STRING, the primitive arrays, and boxed scalars.
      // Unboxed scalars are register-width JNI slots, UNIT is zero-width, and ANY/JSI handles
      // have no binary encoding — Kotlin rejects those at declaration; this is the decode-side backstop.
      static void requireBufferableLeaf(const CppType kind) {
        switch (kind) {
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
            throw std::invalid_argument("This CppType cannot carry kUsesBufferFlag");
        }
      }

      static void requireBufferSafeIfBuffered(const ExpectedType& type) {
        if (type.usesBuffer() && !type.bufferSafe()) {
          throw std::invalid_argument(
            "kUsesBufferFlag on a type that is not buffer-safe (it contains ANY or a JSI handle)"
          );
        }
      }

      int read() {
        if (remaining == 0) {
          throw std::invalid_argument("Invalid payload");
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
