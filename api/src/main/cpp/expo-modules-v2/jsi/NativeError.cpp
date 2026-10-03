#include <expo-modules-v2/jsi/NativeError.h>

#include <expo-modules-v2/jni/JThrowableHelper.h>

namespace expo::modules::v2 {
  facebook::jsi::Object createNativeError(
    facebook::jsi::Runtime& rt,
    const std::string& code,
    const std::string& message,
    const std::string& stack
  ) {
    facebook::jsi::Object error = rt.global()
      .getPropertyAsFunction(rt, "Error")
      .callAsConstructor(
        rt,
        facebook::jsi::String::createFromUtf8(rt, message)
      )
      .getObject(rt);
    if (!code.empty()) {
      error.setProperty(rt, "code", facebook::jsi::String::createFromUtf8(rt, code));
    }
    if (!stack.empty()) {
      // `stack` is JavaScript's own; the Kotlin trace goes beside it under its own name.
      error.setProperty(rt, "nativeStack", facebook::jsi::String::createFromUtf8(rt, stack));
    }
    return error;
  }

  void rethrowPendingAsJSError(facebook::jsi::Runtime& rt, JNIEnv* env) {
    const auto details = JThrowableHelper::takePending(env);
    if (!details) [[likely]] {
      return;
    }

    facebook::jsi::Value error(rt, createNativeError(rt, details->code, details->message, details->stack));
    throw facebook::jsi::JSError(rt, std::move(error));
  }
}
