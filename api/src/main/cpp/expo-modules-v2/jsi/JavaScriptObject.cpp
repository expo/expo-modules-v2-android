#include <expo-modules-v2/jsi/JavaScriptObject.h>

#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <kolibri/class.h>
#include <kolibri/string_utils.h>
#include <kolibri/array.h>

namespace expo::modules::v2::jsi {
  jobject JavaScriptObject::create(
    JNIEnv* env,
    facebook::jsi::Runtime* runtime,
    facebook::jsi::Object object
  ) {
    auto* self = new JavaScriptObject(runtime, std::move(object));
    return constructor.createRaw(env, reinterpret_cast<jlong>(self));
  }

  const facebook::jsi::Object& JavaScriptObject::object() const {
    return *object_;
  }

  facebook::jsi::Runtime& JavaScriptObject::runtime() const {
    return *runtime_;
  }

  jboolean JavaScriptObject::isArray() const noexcept {
    return object().isArray(runtime());
  }

  jboolean JavaScriptObject::isArrayBuffer() const noexcept {
    return object().isArrayBuffer(runtime());
  }

  jboolean JavaScriptObject::hasProperty(JNIEnv* env, jstring name) const {
    const std::string propertyName = kolibri::toStdString(env, name);
    return object().hasProperty(
      runtime(),
      propertyName.c_str()
    );
  }

  jobject JavaScriptObject::getProperty(JNIEnv* env, jstring name) const {
    facebook::jsi::Runtime& rt = runtime();
    const std::string propertyName = kolibri::toStdString(env, name);
    facebook::jsi::Value value = object().getProperty(rt, propertyName.c_str());
    return JavaScriptValue::create(env, &rt, std::move(value));
  }

  jobjectArray JavaScriptObject::getPropertyNames(JNIEnv* env) const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Array names = object().getPropertyNames(rt);
    const size_t size = names.size(rt);
    const jobjectArray result = kolibri::JArray<kolibri::JString>::createRaw(env, size);

    if (size < kolibri::kMaxLocalFrameSize) [[likely]] {
      env->PushLocalFrame(size);
      for (size_t i = 0; i < size; i++) {
        std::string name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        jstring element = kolibri::toJString(env, name);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
      }
      env->PopLocalFrame(nullptr);
    } else {
      for (size_t i = 0; i < size; i++) {
        std::string name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        kolibri::Ref<> element = kolibri::Ref<>::adopt(env, kolibri::toJString(env, name));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
      }
    }
    return result;
  }

  jobjectArray JavaScriptObject::getArray(JNIEnv* env) const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Array array = object().getArray(rt);
    const size_t size = array.size(rt);

    const jobjectArray result = kolibri::JArray<JavaScriptValue>::createRaw(env, size);
    if (size < kolibri::kMaxLocalFrameSize) [[likely]] {
      env->PushLocalFrame(size);
      for (size_t i = 0; i < size; i++) {
        const jobject element = JavaScriptValue::create(env, &rt, array.getValueAtIndex(rt, i));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
      }
      env->PopLocalFrame(nullptr);
    } else {
      for (size_t i = 0; i < size; i++) {
        kolibri::Ref<JavaScriptValue> element = kolibri::Ref<JavaScriptValue>::adopt(
          env,
          JavaScriptValue::create(env, &rt, array.getValueAtIndex(rt, i))
        );
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element.get());
      }
    }
    return result;
  }

  void JavaScriptObject::setBoolProperty(JNIEnv* env, jstring name, jboolean value) const {
    const std::string propertyName = kolibri::toStdString(env, name);
    object().setProperty(
      runtime(),
      propertyName.c_str(),
      facebook::jsi::Value(static_cast<bool>(value))
    );
  }

  void JavaScriptObject::setDoubleProperty(JNIEnv* env, jstring name, jdouble value) const {
    const std::string propertyName = kolibri::toStdString(env, name);
    object().setProperty(
      runtime(),
      propertyName.c_str(),
      facebook::jsi::Value(value)
    );
  }

  void JavaScriptObject::setStringProperty(JNIEnv* env, jstring name, jstring value) const {
    facebook::jsi::Runtime& rt = runtime();
    const std::string propertyName = kolibri::toStdString(env, name);
    facebook::jsi::Value jsValue =
      value
        ? facebook::jsi::Value(facebook::jsi::String::createFromUtf8(rt, kolibri::toStdString(env, value)))
        : facebook::jsi::Value::null();
    object().setProperty(rt, propertyName.c_str(), std::move(jsValue));
  }

  void JavaScriptObject::setJSValueProperty(
    JNIEnv* env,
    jstring name,
    JavaScriptValue* value
  ) const {
    facebook::jsi::Runtime& rt = runtime();
    const std::string propertyName = kolibri::toStdString(env, name);
    facebook::jsi::Value jsValue =
      value
        ? facebook::jsi::Value(rt, value->value())
        : facebook::jsi::Value::null();
    object().setProperty(rt, propertyName.c_str(), jsValue);
  }

  void JavaScriptObject::setObjectProperty(
    JNIEnv* env,
    jstring name,
    JavaScriptObject* value
  ) const {
    facebook::jsi::Runtime& rt = runtime();
    const std::string propertyName = kolibri::toStdString(env, name);
    facebook::jsi::Value jsValue =
      value
        ? facebook::jsi::Value(rt, value->object())
        : facebook::jsi::Value::null();
    object().setProperty(rt, propertyName.c_str(), std::move(jsValue));
  }

  void JavaScriptObject::unsetProperty(JNIEnv* env, jstring name) const {
    facebook::jsi::Runtime& rt = runtime();
    const std::string propertyName = kolibri::toStdString(env, name);
    object().setProperty(rt, propertyName.c_str(), facebook::jsi::Value::undefined());
  }

  JavaScriptObject::JavaScriptObject(
    facebook::jsi::Runtime* runtime,
    facebook::jsi::Object object
  ) : runtime_(runtime),
      object_(std::move(object)) {
    bindToScope(runtime_);
  }

  JavaScriptObject::~JavaScriptObject() {
    releaseScopedResources();
  }

  void JavaScriptObject::registerNatives(JNIEnv* env) {
    kolibri::registerNative<JavaScriptObject>(env)
      .method<&JavaScriptObject::isArray>("isArray")
      .method<&JavaScriptObject::isArrayBuffer>("isArrayBuffer")
      .method<&JavaScriptObject::hasProperty>("hasProperty")
      .method<
        &JavaScriptObject::getProperty,
        kolibri::Ref<JavaScriptValue>(kolibri::NativePointer, jstring)
      >("getProperty")
      .method<
        &JavaScriptObject::getPropertyNames,
        kolibri::Ref<kolibri::JArray<jstring>>(kolibri::NativePointer)
      >("getPropertyNames")
      .method<
        &JavaScriptObject::getArray,
        kolibri::Ref<kolibri::JArray<JavaScriptValue>>(kolibri::NativePointer)
      >("getArray")
      .method<&JavaScriptObject::setBoolProperty>("setBoolProperty")
      .method<&JavaScriptObject::setDoubleProperty>("setDoubleProperty")
      .method<&JavaScriptObject::setStringProperty>("setStringProperty")
      .method<&JavaScriptObject::setJSValueProperty>("setJSValueProperty")
      .method<&JavaScriptObject::setObjectProperty>("setObjectProperty")
      .method<&JavaScriptObject::unsetProperty>("unsetProperty")
      .commit();
  }
} // namespace expo::modules::v2::jsi
