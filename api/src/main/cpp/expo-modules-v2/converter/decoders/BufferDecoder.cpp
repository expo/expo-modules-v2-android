#include <expo-modules-v2/converter/decoders/BufferDecoder.h>

#include <memory>

#include <expo-jsi/ByteArrayBuffer.h>

#include <expo-modules-v2/converter/decoders/BufferDecode.h>
#include <expo-modules-v2/converter/JsiBuilders.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>

#include <kolibri/binary/BinaryString.h>

namespace expo::modules::v2 {
  namespace {
    facebook::jsi::Value decodeString(facebook::jsi::Runtime& rt, kolibri::binary::Reader& in) {
      return readAdaptiveString(
        in,
        [&](const char* bytes, const size_t length) {
          return facebook::jsi::String::createFromAscii(rt, bytes, length);
        },
        [&](const char16_t* units, const size_t count) {
          return facebook::jsi::String::createFromUtf16(rt, units, count);
        }
      );
    }

    /**
     * Reads a non-nullable number, boolean or string [type] into [out] without the out-of-line call
     * and visit `decodeFromBuffer` makes per value. Returns false, having read nothing, for every
     * other type.
     */
    ALWAYS_INLINE bool decodeLeafInline(
      facebook::jsi::Runtime& rt,
      kolibri::binary::Reader& in,
      const ExpectedType& type,
      facebook::jsi::Value& out
    ) {
      if (type.nullable()) {
        return false;
      }

      switch (type.kind()) {
        case CppType::INT:
        case CppType::BOX_INT:
          out = facebook::jsi::Value(static_cast<double>(in.read<int32_t>()));
          return true;
        case CppType::LONG:
        case CppType::BOX_LONG:
          out = facebook::jsi::Value(static_cast<double>(in.read<int64_t>()));
          return true;
        case CppType::FLOAT:
        case CppType::BOX_FLOAT:
          out = facebook::jsi::Value(in.read<float>());
          return true;
        case CppType::DOUBLE:
        case CppType::BOX_DOUBLE:
          out = facebook::jsi::Value(in.read<double>());
          return true;
        case CppType::BOOLEAN:
        case CppType::BOX_BOOLEAN:
          out = facebook::jsi::Value(in.read<uint8_t>() != 0);
          return true;
        case CppType::STRING:
          out = decodeString(rt, in);
          return true;
        default:
          return false;
      }
    }

    ALWAYS_INLINE facebook::jsi::Value decodeValue(
      facebook::jsi::Runtime& rt,
      kolibri::binary::Reader& in,
      const ExpectedType& type
    ) {
      facebook::jsi::Value value;
      if (!decodeLeafInline(rt, in, type, value)) {
        value = decodeFromBuffer(rt, in, type);
      }
      return value;
    }

    /** A record's fields in schema order, into a new object whose properties [plan] names. */
    facebook::jsi::Value decodeRecordFields(
      facebook::jsi::Runtime& rt,
      kolibri::binary::Reader& in,
      const RecordAccessPlan& plan
    ) {
      const RecordSchema& schema = *plan.schema;
      facebook::jsi::Object result(rt);
      for (size_t i = 0; i < schema.fields.size(); i++) {
        result.setProperty(
          rt,
          plan.fieldNames[i],
          decodeValue(rt, in, schema.fields[i].type)
        );
      }
      return result;
    }
  } // namespace

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::INT>() const {
    return facebook::jsi::Value(static_cast<double>(in.read<int32_t>()));
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::BOX_INT>() const {
    return facebook::jsi::Value(static_cast<double>(in.read<int32_t>()));
  }

  // Note: int64 values above 2^53 lose precision; revisit with BigInt support.
  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::LONG>() const {
    return facebook::jsi::Value(static_cast<double>(in.read<int64_t>()));
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::BOX_LONG>() const {
    return facebook::jsi::Value(static_cast<double>(in.read<int64_t>()));
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::FLOAT>() const {
    return facebook::jsi::Value(in.read<float>());
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::BOX_FLOAT>() const {
    return facebook::jsi::Value(in.read<float>());
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::DOUBLE>() const {
    return facebook::jsi::Value(in.read<double>());
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::BOX_DOUBLE>() const {
    return facebook::jsi::Value(in.read<double>());
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::BOOLEAN>() const {
    return facebook::jsi::Value(in.read<uint8_t>() != 0);
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::BOX_BOOLEAN>() const {
    return facebook::jsi::Value(in.read<uint8_t>() != 0);
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::UNIT>() const {
    return facebook::jsi::Value::undefined();
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::STRING>() const {
    return decodeString(rt, in);
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::BOOLEAN_ARRAY>() const {
    const size_t count = in.readCount();
    return buildJsArray(rt, count, [&](size_t) {
      return facebook::jsi::Value(in.read<uint8_t>() != 0);
    });
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::INT_ARRAY>() const {
    const size_t count = in.readCount();
    return buildJsArray(rt, count, [&](size_t) {
      return facebook::jsi::Value(static_cast<double>(in.read<int32_t>()));
    });
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::LONG_ARRAY>() const {
    const size_t count = in.readCount();
    return buildJsArray(rt, count, [&](size_t) {
      return facebook::jsi::Value(static_cast<double>(in.read<int64_t>()));
    });
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::FLOAT_ARRAY>() const {
    const size_t count = in.readCount();
    return buildJsArray(rt, count, [&](size_t) {
      return facebook::jsi::Value(in.read<float>());
    });
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::DOUBLE_ARRAY>() const {
    const size_t count = in.readCount();
    return buildJsArray(rt, count, [&](size_t) {
      return facebook::jsi::Value(in.read<double>());
    });
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::BYTE_ARRAY>() const {
    const size_t size = in.readCount();
    const uint8_t* bytes = in.readBytes(size);
    return facebook::jsi::ArrayBuffer(
      rt,
      std::make_shared<jsi::ByteArrayBuffer>(bytes, size)
    );
  }

  template<>
  facebook::jsi::Value BufferDecoder::operator()<CppType::ANY>() const {
    return decodeFromBufferDynamic(rt, in);
  }

  facebook::jsi::Value BufferDecoder::operator()(const ExpectedType::List& listType) const {
    const ExpectedType& elementType = *listType.element;
    const size_t count = in.readCount();

    return buildJsArray(rt, count, [&](size_t) {
      return decodeValue(rt, in, elementType);
    });
  }

  facebook::jsi::Value BufferDecoder::operator()(const ExpectedType::Map& mapType) const {
    const ExpectedType& valueType = *mapType.value;
    const size_t count = in.readCount();
    RecordPropertyCache& names = RecordPropertyCache::get(rt);
    facebook::jsi::Object result(rt);
    for (size_t i = 0; i < count; i++) {
      readAdaptiveString(
        in,
        [&](const char* bytes, const size_t length) {
          facebook::jsi::Value value = decodeValue(rt, in, valueType);
          if (const facebook::jsi::PropNameID* name = names.asciiKey(rt, {bytes, length})) {
            result.setProperty(rt, *name, std::move(value));
          } else {
            const auto uncached = facebook::jsi::PropNameID::forAscii(rt, bytes, length);
            result.setProperty(rt, uncached, std::move(value));
          }
        },
        [&](const char16_t* units, const size_t stringCount) {
          const auto name = facebook::jsi::PropNameID::forUtf16(rt, units, stringCount);
          result.setProperty(rt, name, decodeValue(rt, in, valueType));
        }
      );
    }
    return result;
  }

  facebook::jsi::Value BufferDecoder::operator()(const ExpectedType::Record& recordType) const {
    const RecordAccessPlan& plan = RecordPropertyCache::get(rt).planFor(rt, recordType.schemaId);
    return decodeRecordFields(rt, in, plan);
  }
}
