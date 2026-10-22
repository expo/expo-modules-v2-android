#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <jni.h>

#include <jsi/jsi.h>

#include <kolibri/binary/BinaryBuffer.h>

namespace expo::modules::v2 {
  namespace detail {
    inline void widenAscii(
      const uint8_t* src,
      uint8_t* dst,
      const size_t num
    ) {
      for (size_t k = num; k-- > 0;) {
        const char16_t unit = src[k];
        std::memcpy(
          dst + k * sizeof(char16_t),
          &unit,
          sizeof(char16_t)
        );
      }
    }

    /**
     * Writes [num] UTF-16 units to [dst] as one byte each and reports whether that was legal.
     */
    [[nodiscard]] inline bool narrowIfAscii(
      const char16_t* src,
      const size_t num,
      uint8_t* dst
    ) {
      char16_t accumulator = 0;
      for (size_t k = 0; k < num; ++k) {
        const char16_t unit = src[k];
        accumulator |= unit;
        dst[k] = static_cast<uint8_t>(unit);
      }
      return (accumulator & 0xFF80) == 0;
    }

    [[nodiscard]] inline bool narrowIfAscii(
      const char16_t* src,
      const size_t num,
      kolibri::binary::BinaryBuffer& out
    ) {
      out.require(num);
      if (!narrowIfAscii(src, num, out.data() + out.size())) {
        return false;
      }

      out.setSize(out.size() + num);
      return true;
    }

    inline facebook::jsi::String utf16String(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      jstring value,
      const jsize length
    ) {
      const jchar* units = env->GetStringChars(value, nullptr);

      struct Release {
        JNIEnv* env;
        jstring value;
        const jchar* units;

        ~Release() { env->ReleaseStringChars(value, units); }
      } release{.env = env, .value = value, .units = units};

      return facebook::jsi::String::createFromUtf16(
        rt,
        reinterpret_cast<const char16_t*>(units),
        static_cast<size_t>(length)
      );
    }

    /**
     * A string too long for the stack buffer is copied out once, by the JNI call that suits its
     * text.
     */
    inline facebook::jsi::String longJsiStringFromJString(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      jstring value,
      const jsize length
    ) {
      constexpr jsize kHeadUnits = 32;
      char16_t head[kHeadUnits];
      env->GetStringRegion(value, 0, kHeadUnits, reinterpret_cast<jchar*>(head));
      const bool headIsAscii = std::ranges::all_of(head, [](const char16_t unit) {
        return unit < 0x80;
      });

      if (headIsAscii && env->GetStringUTFLength(value) == length) {
        const char* bytes = env->GetStringUTFChars(value, nullptr);
        auto result = facebook::jsi::String::createFromAscii(
          rt,
          bytes,
          static_cast<size_t>(length)
        );
        env->ReleaseStringUTFChars(value, bytes);
        return result;
      }

      return utf16String(rt, env, value, length);
    }
  } // namespace detail

  inline facebook::jsi::String jsiStringFromJString(
    facebook::jsi::Runtime& rt,
    JNIEnv* env,
    jstring value
  ) {
    const jsize length = env->GetStringLength(value);

    constexpr jsize kStackUnits = 1024;
    if (length > kStackUnits) [[unlikely]] {
      return detail::longJsiStringFromJString(rt, env, value, length);
    }

    char16_t units[kStackUnits];
    env->GetStringRegion(value, 0, length, reinterpret_cast<jchar*>(units));

    uint8_t ascii[kStackUnits];
    if (detail::narrowIfAscii(units, static_cast<size_t>(length), ascii)) [[likely]] {
      return facebook::jsi::String::createFromAscii(
        rt,
        reinterpret_cast<const char*>(ascii),
        static_cast<size_t>(length)
      );
    }
    return facebook::jsi::String::createFromUtf16(rt, units, static_cast<size_t>(length));
  }

  inline jstring jstringFromJsiString(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::String& str
  ) {
    constexpr size_t kStackUnits = 256;
    const size_t length = str.length(rt);

    jchar stackUnits[kStackUnits];
    std::unique_ptr<jchar[]> heapUnits;
    jchar* units = stackUnits;
    if (length > kStackUnits) {
      heapUnits = std::make_unique_for_overwrite<jchar[]>(length);
      units = heapUnits.get();
    }

    size_t count = 0;
    auto copy = [&](const bool ascii, const void* data, const size_t num) {
      if (ascii) {
        const auto* bytes = static_cast<const uint8_t*>(data);
        for (size_t i = 0; i < num; i++) {
          units[count + i] = bytes[i];
        }
      } else {
        std::memcpy(units + count, data, num * sizeof(jchar));
      }
      count += num;
    };
    str.getStringData(rt, copy);

    return env->NewString(units, static_cast<jsize>(count));
  }

  inline void writeJsiString(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::String& str,
    kolibri::binary::BinaryBuffer& out
  ) {
    const size_t prefixOffset = out.size();
    out.write<int32_t>(0); // patched below, once the payload arm and unit count are known
    const size_t payloadOffset = out.size();
    size_t count = 0; // ASCII bytes or UTF-16 units written so far
    bool isUtf16 = false;

    auto sink = [&](
      const bool ascii,
      const void* data,
      const size_t num
    ) {
      if (num == 0) {
        return;
      }

      if (!isUtf16) {
        if (ascii) [[likely]] {
          out.writeBytes(data, num);
          count += num;
          return;
        }

        // A UTF-16 chunk does not mean the text is UTF-16. Hermes stores anything built at
        // run time - a concatenation, a template literal, `repeat` - as UTF-16 even when every unit
        // is ASCII, and only string literals from the bytecode arrive 8-bit.
        //
        // So narrow speculatively and let the OR-accumulator say afterwards whether that was
        // allowed. The narrowing loop costs about what reading the units costs, and nothing is
        // committed until the answer is in, so the UTF-16 patch below still sees an untouched buffer.
        if (detail::narrowIfAscii(static_cast<const char16_t*>(data), num, out)) {
          count += num;
          return;
        }

        if (count != 0) [[unlikely]] {
          // Earlier chunks went in 8-bit and this one cannot, so what is already there has to grow.
          out.require(count);
          uint8_t* payload = out.data() + payloadOffset;
          detail::widenAscii(payload, payload, count);
          out.setSize(payloadOffset + count * sizeof(char16_t));
        }

        isUtf16 = true;
      }

      const size_t bytes = num * sizeof(char16_t);
      if (ascii) {
        out.require(bytes);
        detail::widenAscii(static_cast<const uint8_t*>(data), out.data() + out.size(), num);
        out.setSize(out.size() + bytes);
      } else [[likely]] {
        out.writeBytes(data, bytes);
      }

      count += num;
    };

    str.getStringData(rt, sink);
    const auto prefix = isUtf16
                          ? -static_cast<int32_t>(count)
                          : static_cast<int32_t>(count);

    out.writeAt(prefixOffset, prefix);
  }
}
