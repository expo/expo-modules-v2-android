#include "BinaryTestPipeline.h"

#include <limits>
#include <stdexcept>

#include <expo-modules-v2/converter/decoders/BufferDecode.h>
#include <expo-modules-v2/converter/encoders/BufferEncode.h>
#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

namespace expo::modules::v2 {
  bool encodeJSIValue(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    kolibri::binary::BinaryBuffer& out
  ) {
    out.clear();
    try {
      encodeToBufferDynamic(rt, value, out);
      return true;
    } catch (const kolibri::binary::BufferOverflow&) {
      out.clear();
      return false;
    }
  }

  bool encodeJSIValue(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& expectedType,
    kolibri::binary::BinaryBuffer& out
  ) {
    out.clear();
    try {
      encodeToBuffer(rt, value, expectedType, out);
      return true;
    } catch (const kolibri::binary::BufferOverflow&) {
      out.clear();
      return false;
    }
  }

  facebook::jsi::Value decodeJSIValue(facebook::jsi::Runtime& rt, const uint8_t* data, size_t size) {
    kolibri::binary::Reader in{data, data + size};
    facebook::jsi::Value result = decodeFromBufferDynamic(rt, in);
    if constexpr (kolibri::binary::kCheckedCodec) {
      if (!in.isOnEnd()) {
        throw std::runtime_error("Trailing bytes in binary payload");
      }
    }
    return result;
  }

  facebook::jsi::Value decodeJSIValue(
    facebook::jsi::Runtime& rt,
    const ExpectedType& type,
    const uint8_t* data,
    size_t size
  ) {
    kolibri::binary::Reader in{data, data + size};
    facebook::jsi::Value result = decodeFromBuffer(rt, in, type);
    if constexpr (kolibri::binary::kCheckedCodec) {
      if (!in.isOnEnd()) {
        throw std::runtime_error("Trailing bytes in binary payload");
      }
    }
    return result;
  }

  namespace detail {
    ExpectedType decodeExpectedTypeCodes(const std::vector<int>& codes) {
      if (codes.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        throw std::invalid_argument("Expected-type encoding is too large");
      }

      std::vector<int32_t> payload;
      payload.reserve(codes.size() + 1);
      payload.push_back(static_cast<int32_t>(codes.size()));
      for (const int code: codes) {
        payload.push_back(static_cast<int32_t>(code));
      }

      const auto* begin = reinterpret_cast<const uint8_t*>(payload.data());
      kolibri::binary::Reader reader{begin, begin + payload.size() * sizeof(int32_t)};
      return decoders::decodeExpectedType(reader);
    }
  }
} // namespace expo::modules::v2
