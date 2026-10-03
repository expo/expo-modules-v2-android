#pragma once

#include <string>

#include <jni.h>
#include <jsi/jsi.h>

namespace expo::modules::v2 {
  /**
   * A JavaScript `Error` for a failure on the native side: [message] as its message, [code] as its
   * `code` unless it is empty, and the Kotlin stack, when there is one, as `nativeStack` beside
   * JavaScript's own.
   */
  facebook::jsi::Object createNativeError(
    facebook::jsi::Runtime& rt,
    const std::string& code,
    const std::string& message,
    const std::string& stack
  );

  /**
   * Throws the pending Java exception, if there is one, as a [createNativeError] error that
   * JavaScript catches. Does nothing when no exception is pending.
   */
  void rethrowPendingAsJSError(facebook::jsi::Runtime& rt, JNIEnv* env);
}
