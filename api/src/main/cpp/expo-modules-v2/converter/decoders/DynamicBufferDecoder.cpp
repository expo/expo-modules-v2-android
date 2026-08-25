#include <expo-modules-v2/converter/decoders/DynamicBufferDecoder.h>

#include <memory>

#include <expo-jsi/ByteArrayBuffer.h>

#include <expo-modules-v2/converter/decoders/BufferDecode.h>
#include <expo-modules-v2/converter/JsiBuilders.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>

#include "kolibri/binary/BinaryString.h"

namespace expo::modules::v2 {
  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::BOOLEAN>() const {
    return facebook::jsi::Value(in.read<uint8_t>() != 0);
  }

  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::INT>() const {
    return facebook::jsi::Value(static_cast<double>(in.read<int32_t>()));
  }

  // Note: int64 values above 2^53 lose precision; revisit with BigInt support.
  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::LONG>() const {
    return facebook::jsi::Value(static_cast<double>(in.read<int64_t>()));
  }

  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::FLOAT>() const {
    return facebook::jsi::Value(in.read<float>());
  }

  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::DOUBLE>() const {
    return facebook::jsi::Value(in.read<double>());
  }

  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::NULLTAG>() const {
    return facebook::jsi::Value::null();
  }

  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::STRING>() const {
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
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::BYTE_ARRAY>() const {
    const size_t size = in.readCount();
    const uint8_t* bytes = in.readBytes(size);
    return facebook::jsi::ArrayBuffer(
      rt,
      std::make_shared<jsi::ByteArrayBuffer>(bytes, size)
    );
  }

  /**
   * One `elemTag` describes every element of the list, so it is visited per element by this same
   * decoder: a bulk run repeats that tag's untagged payload, and TAGGED means each element carries a
   * tag of its own - which is exactly what the TAGGED handler reads.
   */
  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::LIST>() const {
    const size_t count = in.readCount();
    const Tag elementTag = in.readTag();
    return buildJsArray(rt, count, [&](size_t) {
      return visitTag<facebook::jsi::Value>(elementTag, *this);
    });
  }

  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::MAP>() const {
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
      result.setProperty(rt, name, decodeFromBufferDynamic(rt, in));
    }
    return result;
  }

  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::EXTERNAL_SCHEMA>() const {
    RecordPropertyCache& recordProperties = RecordPropertyCache::get(rt);
    const RecordAccessPlan& plan = recordProperties.planFor(rt, in.read<int32_t>());
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

  template<>
  facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::TAGGED>() const {
    return decodeFromBufferDynamic(rt, in);
  }
}
