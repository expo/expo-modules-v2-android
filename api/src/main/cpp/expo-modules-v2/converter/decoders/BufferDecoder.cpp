#include <expo-modules-v2/converter/decoders/BufferDecoder.h>

#include <memory>

#include <expo-jsi/ByteArrayBuffer.h>

#include <expo-modules-v2/converter/decoders/BufferDecode.h>
#include <expo-modules-v2/converter/JsiBuilders.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>

#include <kolibri/binary/BinaryString.h>

namespace expo::modules::v2 {
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
      return decodeFromBuffer(rt, in, elementType);
    });
  }

  facebook::jsi::Value BufferDecoder::operator()(const ExpectedType::Map& mapType) const {
    const ExpectedType& valueType = *mapType.value;
    const size_t count = in.readCount();
    facebook::jsi::Object result(rt);
    for (size_t i = 0; i < count; i++) {
      facebook::jsi::PropNameID name = readAdaptiveString(
        in,
        [&](const char* bytes, const size_t length) {
          return facebook::jsi::PropNameID::forAscii(rt, bytes, length);
        },
        [&](const char16_t* units, const size_t stringCount) {
          return facebook::jsi::PropNameID::forUtf16(rt, units, stringCount);
        }
      );
      result.setProperty(rt, name, decodeFromBuffer(rt, in, valueType));
    }
    return result;
  }

  facebook::jsi::Value BufferDecoder::operator()(const ExpectedType::Record& recordType) const {
    RecordPropertyCache& recordProperties = RecordPropertyCache::get(rt);
    const RecordAccessPlan& plan = recordProperties.planFor(rt, recordType.schemaId);
    const RecordSchema& schema = *plan.schema;

    facebook::jsi::Object result(rt);
    for (size_t i = 0; i < schema.fields.size(); i++) {
      const RecordFieldSpec& field = schema.fields[i];
      result.setProperty(
        rt,
        plan.fieldNames[i],
        decodeFromBuffer(rt, in, field.type)
      );
    }
    return result;
  }
}
