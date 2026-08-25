#pragma once

#include <cstdint>
#include <cstring>
#include <jni.h>

#include <jsi/jsi.h>

#include <kolibri/binary/BinaryBuffer.h>

namespace expo::modules::v2 {
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
   * Writes [num] UTF-16 units as one byte each and reports whether that was legal.
   */
  [[nodiscard]] inline bool narrowIfAscii(
    const char16_t* src,
    const size_t num,
    kolibri::binary::BinaryBuffer& out
  ) {
    out.require(num);
    uint8_t* dst = out.data() + out.size();

    char16_t accumulator = 0;
    for (size_t k = 0; k < num; ++k) {
      const char16_t unit = src[k];
      accumulator |= unit;
      dst[k] = static_cast<uint8_t>(unit);
    }

    if ((accumulator & 0xFF80) != 0) {
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

  inline facebook::jsi::String jsiStringFromJString(
    facebook::jsi::Runtime& rt,
    JNIEnv* env,
    jstring value
  ) {
    const jsize length = env->GetStringLength(value);
    if (length == 0) {
      return facebook::jsi::String::createFromAscii(rt, "", 0);
    }

    constexpr jsize kAsciiWindow = 32;
    constexpr jsize kWindowMinLength = 256;

    if (length >= kWindowMinLength) {
      jchar head[kAsciiWindow];
      env->GetStringRegion(value, 0, kAsciiWindow, head);
      jchar accumulator = 0;
      for (jsize i = 0; i < kAsciiWindow; ++i) {
        accumulator |= head[i];
      }
      if (accumulator >= 0x80) {
        return utf16String(rt, env, value, length);
      }
    }

    if (env->GetStringUTFLength(value) == length) {
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
        if (narrowIfAscii(static_cast<const char16_t*>(data), num, out)) {
          count += num;
          return;
        }

        if (count != 0) [[unlikely]] {
          // Earlier chunks went in 8-bit and this one cannot, so what is already there has to grow.
          out.require(count);
          uint8_t* payload = out.data() + payloadOffset;
          widenAscii(payload, payload, count);
          out.setSize(payloadOffset + count * sizeof(char16_t));
        }

        isUtf16 = true;
      }

      const size_t bytes = num * sizeof(char16_t);
      if (ascii) {
        out.require(bytes);
        widenAscii(static_cast<const uint8_t*>(data), out.data() + out.size(), num);
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
