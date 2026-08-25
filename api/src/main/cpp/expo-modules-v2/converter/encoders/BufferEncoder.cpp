#include <expo-modules-v2/converter/encoders/BufferEncoder.h>

#include <expo-modules-v2/converter/encoders/BufferEncode.h>
#include <expo-modules-v2/converter/encoders/EncodeCommon.h>
#include <expo-modules-v2/converter/JsiStringCodec.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>

namespace expo::modules::v2 {
  template<>
  void BufferEncoder::operator()<CppType::INT>() const {
    out.write<int32_t>(static_cast<int32_t>(value.asNumber()));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_INT>() const {
    out.write<int32_t>(static_cast<int32_t>(value.asNumber()));
  }

  template<>
  void BufferEncoder::operator()<CppType::LONG>() const {
    out.write<int64_t>(static_cast<int64_t>(value.asNumber()));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_LONG>() const {
    out.write<int64_t>(static_cast<int64_t>(value.asNumber()));
  }

  template<>
  void BufferEncoder::operator()<CppType::FLOAT>() const {
    out.write<float>(static_cast<float>(value.asNumber()));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_FLOAT>() const {
    out.write<float>(static_cast<float>(value.asNumber()));
  }

  template<>
  void BufferEncoder::operator()<CppType::DOUBLE>() const {
    out.write<double>(value.asNumber());
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_DOUBLE>() const {
    out.write<double>(value.asNumber());
  }

  template<>
  void BufferEncoder::operator()<CppType::BOOLEAN>() const {
    out.write<uint8_t>(value.asBool());
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_BOOLEAN>() const {
    out.write<uint8_t>(value.asBool());
  }

  template<>
  void BufferEncoder::operator()<CppType::BOOLEAN_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<uint8_t>(array.getValueAtIndex(rt, i).asBool());
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::INT_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<int32_t>(static_cast<int32_t>(array.getValueAtIndex(rt, i).asNumber()));
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::LONG_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<int64_t>(static_cast<int64_t>(array.getValueAtIndex(rt, i).asNumber()));
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::FLOAT_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<float>(static_cast<float>(array.getValueAtIndex(rt, i).asNumber()));
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::DOUBLE_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<double>(array.getValueAtIndex(rt, i).asNumber());
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::BYTE_ARRAY>() const {
    const facebook::jsi::ArrayBuffer arrayBuffer = value.asObject(rt).getArrayBuffer(rt);
    const size_t size = arrayBuffer.size(rt);

    out.write<>(static_cast<int32_t>(size));
    out.writeBytes(arrayBuffer.data(rt), size);
  }

  template<>
  void BufferEncoder::operator()<CppType::UNIT>() const {
  }

  template<>
  void BufferEncoder::operator()<CppType::STRING>() const {
    writeJsiString(rt, value.asString(rt), out);
  }

  template<>
  void BufferEncoder::operator()<CppType::ANY>() const {
    encodeToBufferDynamic(rt, value, out);
  }

  void BufferEncoder::operator()(const ExpectedType::List& listType) const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const ExpectedType& elementType = *listType.element;
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      encodeToBuffer(rt, array.getValueAtIndex(rt, i), elementType, out);
    }
  }

  void BufferEncoder::operator()(const ExpectedType::Map& mapType) const {
    const facebook::jsi::Object object = value.asObject(rt);
    const ExpectedType& valueType = *mapType.value;
    const facebook::jsi::Array names = object.getPropertyNames(rt);
    const size_t size = names.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      facebook::jsi::String key = names.getValueAtIndex(rt, i).getString(rt);
      writeJsiString(rt, key, out);
      encodeToBuffer(rt, object.getProperty(rt, key), valueType, out);
    }
  }

  void BufferEncoder::operator()(const ExpectedType::Record& recordType) const {
    RecordPropertyCache& recordProperties = RecordPropertyCache::get(rt);
    const RecordAccessPlan& plan = recordProperties.planFor(rt, recordType.schemaId);
    const RecordSchema& schema = *plan.schema;
    const facebook::jsi::Object object = value.asObject(rt);
    for (size_t i = 0; i < schema.fields.size(); i++) {
      const RecordFieldSpec& field = schema.fields[i];
      const facebook::jsi::Value fieldValue = readRecordField(
        rt,
        object,
        schema,
        field,
        plan.fieldNames[i]
      );

      // Handle optional fields
      if (field.optional) [[unlikely]] {
        const bool present = !fieldValue.isUndefined();
        out.write<uint8_t>(present ? 1 : 0);
        if (!present) {
          continue;
        }
      }

      encodeToBuffer(rt, fieldValue, field.type, out);
    }
  }
} // namespace expo::modules::v2
