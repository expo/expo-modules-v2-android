#pragma once

#include <cstddef>
#include <memory>
#include <new>
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

#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  class JsArrayAppender {
  public:
    // ReSharper disable CppPossiblyUninitializedMember
    JsArrayAppender(
      facebook::jsi::Runtime& rt,
      facebook::jsi::Array& array
    ) : rt_(rt),
        jsArray_(array) {
    }

    // ReSharper restore CppPossiblyUninitializedMember

    JsArrayAppender(const JsArrayAppender&) = delete;

    JsArrayAppender& operator=(const JsArrayAppender&) = delete;

    ~JsArrayAppender() { release(); }

    ALWAYS_INLINE void append(facebook::jsi::Value value) {
      std::byte* index = storage_ + pending_ * sizeof(facebook::jsi::Value);
      new(index) facebook::jsi::Value(std::move(value));
      if (++pending_ == kChunkSize) [[unlikely]] {
        flush();
      }
    }

    void flush() {
      if (pending_ != 0) {
        jsArray_.push(rt_, slot(0), pending_);
        release();
      }
    }

  private:
    static constexpr size_t kChunkSize = 64;

    ALWAYS_INLINE const facebook::jsi::Value* slot(const size_t index) const {
      const std::byte* slotPtr = storage_ + index * sizeof(facebook::jsi::Value);
      return std::launder(
        reinterpret_cast<const facebook::jsi::Value*>(slotPtr)
      );
    }

    void release() noexcept {
      for (size_t i = 0; i < pending_; i++) {
        slot(i)->~Value();
      }
      pending_ = 0;
    }

    facebook::jsi::Runtime& rt_;
    facebook::jsi::Array& jsArray_;
    alignas(facebook::jsi::Value) std::byte storage_[kChunkSize * sizeof(facebook::jsi::Value)];
    size_t pending_ = 0;
  };

  template<typename MakeOne>
  ALWAYS_INLINE facebook::jsi::Value buildJsArray(facebook::jsi::Runtime& rt, size_t count, MakeOne&& makeOne) {
    facebook::jsi::Array result(rt, 0);
    JsArrayAppender appender(rt, result);
    for (size_t i = 0; i < count; i++) {
      appender.append(makeOne(i));
    }
    appender.flush();
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
    facebook::jsi::Array result(rt, 0);
    JsArrayAppender appender(rt, result);
    array->forEach(env, [&](const jsize, const Element element) {
      appender.append(makeOne(element));
    });
    appender.flush();
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
      throwRuntimeError({
        "The returned ",
        (isObjectHandle ? "JavaScriptObject" : "JavaScriptValue"),
        " belongs to a different runtime",
      });
    }
    if constexpr (isObjectHandle) {
      return facebook::jsi::Value(rt, handle->object());
    } else {
      return facebook::jsi::Value(rt, handle->value());
    }
  }
} // namespace expo::modules::v2
