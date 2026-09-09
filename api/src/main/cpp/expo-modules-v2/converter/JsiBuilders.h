#pragma once

#include <memory>
#include <span>
#include <stdexcept>
#include <string>
#include <utility>

#include <jni.h>
#include <jsi/jsi.h>

#include <expo-jsi/ByteArrayBuffer.h>
#include <kolibri/NativeObject.h>
#include <kolibri/Ref.h>
#include <kolibri/array.h>

namespace expo::modules::v2 {
  template<typename MakeOne>
  ALWAYS_INLINE facebook::jsi::Value buildJsArray(facebook::jsi::Runtime& rt, size_t count, MakeOne&& makeOne) {
    facebook::jsi::Array result(rt, count);
    for (size_t i = 0; i < count; i++) {
      result.setValueAtIndex(rt, i, makeOne(i));
    }
    return result;
  }

  /**
   * Decodes a primitive Java array into a JS array. The elements stream through kolibri's stack
   * chunk (`forEach`), so no native buffer the size of the array is allocated per call.
   * `makeOne(element)` converts one element into a `jsi::Value`.
   */
  template<typename Element, typename MakeOne>
  ALWAYS_INLINE facebook::jsi::Value decodeJniArray(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object,
    MakeOne&& makeOne
  ) {
    const kolibri::UnownedRef<kolibri::JArray<Element>> array(object);
    facebook::jsi::Array result(rt, static_cast<size_t>(array->size(env)));
    array->forEach(env, [&](const jsize index, const Element element) {
      result.setValueAtIndex(rt, static_cast<size_t>(index), makeOne(element));
    });
    return result;
  }

  /**
   * Decodes a `byte[]` into an ArrayBuffer with a single copy: the JNI region lands directly in
   * the buffer the ArrayBuffer owns, with no intermediate vector.
   */
  ALWAYS_INLINE facebook::jsi::Value decodeJniByteArray(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object
  ) {
    const kolibri::UnownedRef<kolibri::JByteArray> array(object);
    auto buffer = std::make_shared<expo::jsi::ByteArrayBuffer>(static_cast<size_t>(array->size(env)));
    if (buffer->size() > 0) {
      array->getRegion(
        env,
        0,
        std::span<jbyte>(reinterpret_cast<jbyte*>(buffer->data()), buffer->size())
      );
    }
    return facebook::jsi::ArrayBuffer(rt, std::move(buffer));
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
