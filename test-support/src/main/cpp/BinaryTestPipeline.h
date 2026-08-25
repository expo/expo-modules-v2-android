#pragma once

#include <jsi/jsi.h>
#include <vector>

#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <kolibri/binary/BinaryCodec.h>

namespace expo::modules::v2 {

  // The test/benchmark endpoint of the binary codec, JSI side only — no Java crossing. Production
  // values never route through here: dynamic (`ANY`) values cross element-wise as JNI objects —
  // they can contain JSI handles, which must never be serialized — and declared complex types
  // either ride trampoline payloads (buffer-safe) or the same JNI object path (JSFunctions.cpp /
  // Kotlin `Trampoline`). These wrappers back the `__binaryNativeRoundTrip` and
  // `__convertRoundTrip` test host functions and the folly benchmark.
  //
  // The buffers are fixed-size: a payload that doesn't fit makes `encodeJSIValue` report failure
  // and the caller falls back to the element-wise path, so correctness never depends on payload
  // size.

  // ==== Whole-payload buffer transactions ========================================================
  //
  // The production bridge drives `encodeToBuffer` / `decodeFromBuffer` directly, because one
  // trampoline payload holds several consecutive values (see JniMethodInvoker.cpp). Here every
  // payload is exactly one value, so these wrappers own the buffer lifecycle around a single
  // encode or decode: clear-then-rollback on overflow, and the trailing-bytes check on decode.

  /**
   * Serializes `value` into `out` (dynamic: the JS value picks the encoding, mirroring
   * `encodeToJniDynamic`).
   *
   * Returns false when the payload exceeds the fixed buffer — the caller falls back to the
   * element-wise path. JS functions and other unconvertible values throw
   * `facebook::jsi::JSError`, exactly like the element-wise converters.
   */
  [[nodiscard]] bool encodeJSIValue(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    kolibri::binary::BinaryBuffer& out
  );

  /**
   * Typed variant driven by an [ExpectedType]: leaves coerce exactly like `fromJSIValue<T>`
   * (INT truncates, STRING requires a string, ...). Same overflow contract as the dynamic
   * overload.
   */
  [[nodiscard]] bool encodeJSIValue(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& expectedType,
    kolibri::binary::BinaryBuffer& out
  );

  /**
   * Decodes one DYNAMIC (tagged) payload from `[data, data + size)` into a
   * `facebook::jsi::Value`.
   */
  facebook::jsi::Value decodeJSIValue(facebook::jsi::Runtime& rt, const uint8_t* data, size_t size);

  /**
   * Decodes one SCHEMA-DIRECTED (untagged) payload laid out per `type` — the declared type both
   * ends know; the payload carries only data.
   */
  facebook::jsi::Value decodeJSIValue(
    facebook::jsi::Runtime& rt,
    const ExpectedType& type,
    const uint8_t* data,
    size_t size
  );

  namespace detail {
    /** Adapts a test-only code vector to the production length-prefixed descriptor reader. */
    ExpectedType decodeExpectedTypeCodes(const std::vector<int>& codes);
  }
} // namespace expo::modules::v2
