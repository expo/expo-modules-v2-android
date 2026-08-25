#include <expo-modules-v2/jsi/JavaScriptValue.h>

#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <kolibri/string_utils.h>

#include "kolibri/array.h"

namespace expo::modules::v2::jsi {
  jobject JavaScriptValue::create(
    JNIEnv* env,
    facebook::jsi::Runtime* runtime,
    facebook::jsi::Value value
  ) {
    auto* self = new JavaScriptValue(runtime, std::move(value));
    return constructor.createRaw(env, reinterpret_cast<jlong>(self));
  }

  const facebook::jsi::Value& JavaScriptValue::value() const {
    return value_;
  }

  facebook::jsi::Runtime& JavaScriptValue::runtime() const {
    return *runtime_;
  }

  JavaScriptValue::Kind JavaScriptValue::kind() const noexcept {
    const facebook::jsi::Value& v = value();
    if (v.isUndefined()) {
      return Kind::Undefined;
    }
    if (v.isNull()) {
      return Kind::Null;
    }
    if (v.isBool()) {
      return Kind::Boolean;
    }
    if (v.isNumber()) {
      return Kind::Number;
    }
    if (v.isString()) {
      return Kind::String;
    }
    if (v.isSymbol()) {
      return Kind::Symbol;
    }
    if (v.isBigInt()) {
      return Kind::BigInt;
    }

    return Kind::Object;
  }

  jint JavaScriptValue::kindCode() const noexcept {
    return static_cast<jint>(kind());
  }

  jboolean JavaScriptValue::isNull() const noexcept {
    return value().isNull();
  }

  jboolean JavaScriptValue::isUndefined() const noexcept {
    return value().isUndefined();
  }

  jboolean JavaScriptValue::isBool() const noexcept {
    return value().isBool();
  }

  jboolean JavaScriptValue::isNumber() const noexcept {
    return value().isNumber();
  }

  jboolean JavaScriptValue::isString() const noexcept {
    return value().isString();
  }

  jboolean JavaScriptValue::isSymbol() const noexcept {
    return value().isSymbol();
  }

  jboolean JavaScriptValue::isObject() const noexcept {
    return value().isObject();
  }

  jboolean JavaScriptValue::isFunction() const noexcept {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Value& v = value();
    return v.isObject() && v.getObject(rt).isFunction(rt);
  }

  jboolean JavaScriptValue::isArray() const noexcept {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Value& v = value();
    return v.isObject() && v.getObject(rt).isArray(rt);
  }

  jboolean JavaScriptValue::getBool() const {
    const facebook::jsi::Value& v = value();
    return v.getBool();
  }

  jdouble JavaScriptValue::getDouble() const {
    const facebook::jsi::Value& v = value();
    return v.getNumber();
  }

  jstring JavaScriptValue::getString(JNIEnv* env) const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Value& v = value();
    return kolibri::toJString(env, v.getString(rt).utf8(rt));
  }

  jobject JavaScriptValue::getObject(JNIEnv* env) const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Value& v = value();
    return JavaScriptObject::create(env, &rt, v.getObject(rt));
  }

  jobjectArray JavaScriptValue::getArray(JNIEnv* env) const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Value& v = value();
    const facebook::jsi::Array array = v.getObject(rt).getArray(rt);
    const size_t size = array.size(rt);
    const jobjectArray result = env->NewObjectArray(
      static_cast<jsize>(size),
      javaClass(env),
      nullptr
    );

    if (size < kolibri::kMaxLocalFrameSize) [[likely]] {
      env->PushLocalFrame(size);
      for (size_t i = 0; i < size; i++) {
        const jobject element = create(env, &rt, array.getValueAtIndex(rt, i));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
      }
      env->PopLocalFrame(nullptr);
    } else {
      for (size_t i = 0; i < size; i++) {
        const jobject element = create(env, &rt, array.getValueAtIndex(rt, i));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
        env->DeleteLocalRef(element);
      }
    }

    return result;
  }

  void JavaScriptValue::onScopeInvalidated() {
    value_ = facebook::jsi::Value::undefined();
  }

  JavaScriptValue::JavaScriptValue(facebook::jsi::Runtime* runtime, facebook::jsi::Value value)
    : runtime_(runtime),
      value_(std::move(value)) {
    bindToScope(runtime_);
  }

  JavaScriptValue::~JavaScriptValue() {
    releaseScopedResources();
  }

  void JavaScriptValue::registerNatives(JNIEnv* env) {
    kolibri::registerNative<JavaScriptValue>(env)
      .method<&JavaScriptValue::kindCode>("kindCode")
      .method<&JavaScriptValue::isNull>("isNull")
      .method<&JavaScriptValue::isUndefined>("isUndefined")
      .method<&JavaScriptValue::isBool>("isBool")
      .method<&JavaScriptValue::isNumber>("isNumber")
      .method<&JavaScriptValue::isString>("isString")
      .method<&JavaScriptValue::isSymbol>("isSymbol")
      .method<&JavaScriptValue::isFunction>("isFunction")
      .method<&JavaScriptValue::isArray>("isArray")
      .method<&JavaScriptValue::isObject>("isObject")
      .method<&JavaScriptValue::getBool>("getBool")
      .method<&JavaScriptValue::getDouble>("getDouble")
      .method<&JavaScriptValue::getString>("getString")
      .method<
        &JavaScriptValue::getObject,
        kolibri::Ref<JavaScriptObject>(kolibri::NativePointer)
      >("getObject")
      .method<
        &JavaScriptValue::getArray,
        kolibri::Ref<kolibri::JArray<JavaScriptValue>>(kolibri::NativePointer)
      >("getArray")
      .commit();
  }
} // namespace expo::modules::v2::jsi
