#include <expo-modules-v2/converter/encoders/BufferEncoder.h>

#include <expo-modules-v2/converter/ByteView.h>
#include <expo-modules-v2/converter/encoders/BufferEncode.h>
#include <expo-modules-v2/converter/encoders/EncodeCommon.h>
#include <expo-modules-v2/converter/JsiStringCodec.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>

namespace expo::modules::v2 {
  namespace {
    /**
     * Writes [value] for a non-nullable number, boolean or string [type], when it holds that JS
     * type, without the out-of-line call and visit `encodeToBuffer` makes per value. Returns false,
     * having written nothing, for every other type and value - so a wrong or missing value still
     * reaches `encodeToBuffer`, which reports it. A string is moved out of [value].
     */
    ALWAYS_INLINE bool encodeLeafInline(
      facebook::jsi::Runtime& rt,
      facebook::jsi::Value& value,
      const ExpectedType& type,
      kolibri::binary::BinaryBuffer& out
    ) {
      if (type.nullable()) {
        return false;
      }

      switch (type.kind()) {
        case CppType::INT:
        case CppType::BOX_INT:
          if (!value.isNumber()) {
            return false;
          }
          out.write<int32_t>(static_cast<int32_t>(value.getNumber()));
          return true;
        case CppType::LONG:
        case CppType::BOX_LONG:
          if (!value.isNumber()) {
            return false;
          }
          out.write<int64_t>(static_cast<int64_t>(value.getNumber()));
          return true;
        case CppType::FLOAT:
        case CppType::BOX_FLOAT:
          if (!value.isNumber()) {
            return false;
          }
          out.write<float>(static_cast<float>(value.getNumber()));
          return true;
        case CppType::DOUBLE:
        case CppType::BOX_DOUBLE:
          if (!value.isNumber()) {
            return false;
          }
          out.write<double>(value.getNumber());
          return true;
        case CppType::BOOLEAN:
        case CppType::BOX_BOOLEAN:
          if (!value.isBool()) {
            return false;
          }
          out.write<uint8_t>(value.getBool() ? 1 : 0);
          return true;
        case CppType::STRING:
          if (!value.isString()) {
            return false;
          }
          writeJsiString(rt, std::move(value).getString(rt), out);
          return true;
        default:
          return false;
      }
    }

    /** The fields of [object] in schema order, as [plan] names them. */
    void encodeRecordFields(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object,
      const RecordAccessPlan& plan,
      kolibri::binary::BinaryBuffer& out
    ) {
      const RecordSchema& schema = *plan.schema;
      for (size_t i = 0; i < schema.fields.size(); i++) {
        const RecordFieldSpec& field = schema.fields[i];
        facebook::jsi::Value fieldValue = readRecordField(
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

        if (!encodeLeafInline(rt, fieldValue, field.type, out)) {
          encodeToBuffer(rt, fieldValue, field.type, out);
        }
      }
    }
  } // namespace

  template<>
  void BufferEncoder::operator()<CppType::INT>() const {
    out.write<int32_t>(static_cast<int32_t>(numberOf(rt, value)));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_INT>() const {
    out.write<int32_t>(static_cast<int32_t>(numberOf(rt, value)));
  }

  template<>
  void BufferEncoder::operator()<CppType::LONG>() const {
    out.write<int64_t>(static_cast<int64_t>(numberOf(rt, value)));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_LONG>() const {
    out.write<int64_t>(static_cast<int64_t>(numberOf(rt, value)));
  }

  template<>
  void BufferEncoder::operator()<CppType::FLOAT>() const {
    out.write<float>(static_cast<float>(numberOf(rt, value)));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_FLOAT>() const {
    out.write<float>(static_cast<float>(numberOf(rt, value)));
  }

  template<>
  void BufferEncoder::operator()<CppType::DOUBLE>() const {
    out.write<double>(numberOf(rt, value));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_DOUBLE>() const {
    out.write<double>(numberOf(rt, value));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOOLEAN>() const {
    out.write<uint8_t>(boolOf(rt, value));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOX_BOOLEAN>() const {
    out.write<uint8_t>(boolOf(rt, value));
  }

  template<>
  void BufferEncoder::operator()<CppType::BOOLEAN_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<uint8_t>(boolOf(rt, array.getValueAtIndex(rt, i)));
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::INT_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<int32_t>(static_cast<int32_t>(numberOf(rt, array.getValueAtIndex(rt, i))));
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::LONG_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<int64_t>(static_cast<int64_t>(numberOf(rt, array.getValueAtIndex(rt, i))));
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::FLOAT_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<float>(static_cast<float>(numberOf(rt, array.getValueAtIndex(rt, i))));
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::DOUBLE_ARRAY>() const {
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const size_t size = array.size(rt);

    out.write<>(static_cast<int32_t>(size));

    for (size_t i = 0; i < size; i++) {
      out.write<double>(numberOf(rt, array.getValueAtIndex(rt, i)));
    }
  }

  template<>
  void BufferEncoder::operator()<CppType::BYTE_ARRAY>() const {
    const ByteView bytes = byteViewOf(rt, value);

    out.write<>(static_cast<int32_t>(bytes.size));
    out.writeBytes(bytes.data, bytes.size);
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
      facebook::jsi::Value element = array.getValueAtIndex(rt, i);
      if (!encodeLeafInline(rt, element, elementType, out)) {
        encodeToBuffer(rt, element, elementType, out);
      }
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
      facebook::jsi::Value item = object.getProperty(rt, key);
      if (!encodeLeafInline(rt, item, valueType, out)) {
        encodeToBuffer(rt, item, valueType, out);
      }
    }
  }

  void BufferEncoder::operator()(const ExpectedType::Record& recordType) const {
    const RecordAccessPlan& plan = RecordPropertyCache::get(rt).planFor(rt, recordType.schemaId);
    encodeRecordFields(rt, value.asObject(rt), plan, out);
  }
} // namespace expo::modules::v2
