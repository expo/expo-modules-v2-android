#include <expo-modules-v2/converter/encoders/BufferEncode.h>

#include <stdexcept>

#include <expo-modules-v2/converter/encoders/BufferEncoder.h>
#include <expo-modules-v2/converter/encoders/EncodeCommon.h>
#include <expo-modules-v2/converter/JsiStringCodec.h>

namespace expo::modules::v2 {
  using kolibri::binary::BinaryBuffer;
  using kolibri::binary::Tag;

  namespace {
    void expandRawRunToTagged(
      BinaryBuffer& out,
      const size_t start,
      const size_t count,
      const size_t width,
      Tag tag
    ) {
      out.require(count); // one tag byte per element
      uint8_t* base = out.data();
      for (size_t k = count; k-- > 0;) {
        std::memmove(base + start + k * (width + 1) + 1, base + start + k * width, width);
        base[start + k * (width + 1)] = static_cast<uint8_t>(tag);
      }
      out.setSize(start + count * (width + 1));
    }

    size_t writeDynArraySpeculative(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Array& array,
      const size_t size,
      BinaryBuffer& out
    ) {
      out.writeTag(Tag::LIST);
      out.write<>(static_cast<int32_t>(size));

      const size_t elemTagOffset = out.size();
      size_t i = 0;
      if (size > 0 && array.getValueAtIndex(rt, 0).isNumber()) {
        out.writeTag(Tag::DOUBLE);
        for (; i < size; i++) {
          facebook::jsi::Value value = array.getValueAtIndex(rt, i);
          if (!value.isNumber()) {
            break;
          }
          out.write<double>(value.getNumber());
        }

        if (i == size) {
          return i;
        }

        expandRawRunToTagged(out, elemTagOffset + 1, i, sizeof(double), Tag::DOUBLE);
        out.data()[elemTagOffset] = static_cast<uint8_t>(Tag::TAGGED);
      } else {
        out.writeTag(Tag::TAGGED);
      }
      return i;
    }
  } // namespace

  void encodeToBuffer(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type,
    BinaryBuffer& out
  ) {
    const bool isNull = value.isUndefined() || value.isNull();
    if (isNull && !type.nullable()) [[unlikely]] {
      throwNullInNonNullable(rt, type);
    }

    if (type.nullable()) {
      out.write<uint8_t>(isNull ? 0 : 1);
    }

    if (isNull) {
      return;
    }

    return type.visit<void>(BufferEncoder{
      .rt = rt,
      .value = value,
      .out = out
    });
  }

  void encodeToBufferDynamic(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    BinaryBuffer& out
  ) {
    if (value.isUndefined() || value.isNull()) {
      return out.writeTag(Tag::NULLTAG);
    }

    if (value.isBool()) {
      out.writeTag(Tag::BOOLEAN);
      return out.write<uint8_t>(value.getBool() ? 1 : 0);
    }

    if (value.isNumber()) {
      out.writeTag(Tag::DOUBLE);
      return out.write<double>(value.getNumber());
    }

    if (value.isString()) {
      out.writeTag(Tag::STRING);
      return writeJsiString(rt, value.getString(rt), out);
    }

    if (value.isObject()) {
      const facebook::jsi::Object object = value.getObject(rt);
      if (object.isArray(rt)) {
        const facebook::jsi::Array array = object.getArray(rt);
        const size_t size = array.size(rt);
        for (size_t i = writeDynArraySpeculative(rt, array, size, out); i < size; i++) {
          encodeToBufferDynamic(rt, array.getValueAtIndex(rt, i), out);
        }
        return;
      }

      if (object.isFunction(rt)) {
        throw facebook::jsi::JSError(rt, "Cannot convert a JS function to a Java value"); // TODO: callbacks
      }

      if (object.isArrayBuffer(rt)) {
        const facebook::jsi::ArrayBuffer arrayBuffer = object.getArrayBuffer(rt);
        const size_t size = arrayBuffer.size(rt);

        out.writeTag(Tag::BYTE_ARRAY);
        out.write<>(static_cast<int32_t>(size));
        out.writeBytes(arrayBuffer.data(rt), size);

        return;
      }

      const facebook::jsi::Array names = object.getPropertyNames(rt);
      const size_t size = names.size(rt);

      out.writeTag(Tag::MAP);
      out.write<>(static_cast<int32_t>(size));

      for (size_t i = 0; i < size; i++) {
        facebook::jsi::String key = names.getValueAtIndex(rt, i).getString(rt);
        writeJsiString(rt, key, out);
        encodeToBufferDynamic(rt, object.getProperty(rt, key), out);
      }

      return;
    }

    throw facebook::jsi::JSError(rt, "Cannot convert this JS value to a Java value");
  }
} // namespace expo::modules::v2
