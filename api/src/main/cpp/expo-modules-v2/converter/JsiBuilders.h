#pragma once

#include <stdexcept>
#include <string>

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/NativeObject.h>

namespace expo::modules::v2 {
  template<typename MakeOne>
  ALWAYS_INLINE facebook::jsi::Value buildJsArray(facebook::jsi::Runtime& rt, size_t count, MakeOne&& makeOne) {
    facebook::jsi::Array result(rt, count);
    for (size_t i = 0; i < count; i++) {
      result.setValueAtIndex(rt, i, makeOne(i));
    }
    return result;
  }

  template<typename Handle>
  ALWAYS_INLINE facebook::jsi::Value unwrapJsiHandle(JNIEnv* env, facebook::jsi::Runtime& rt, jobject object) {
    auto* handle = kolibri::JNativeObject::nativeThis<Handle>(env, object);
    constexpr bool isObjectHandle = requires { handle->object(); };
    if (&handle->runtime() != &rt) {
      throw std::runtime_error(
        std::string("The returned ") + (isObjectHandle ? "JavaScriptObject" : "JavaScriptValue") +
        " belongs to a different runtime"
      );
    }
    if constexpr (isObjectHandle) {
      return facebook::jsi::Value(rt, handle->object());
    } else {
      return facebook::jsi::Value(rt, handle->value());
    }
  }
} // namespace expo::modules::v2
