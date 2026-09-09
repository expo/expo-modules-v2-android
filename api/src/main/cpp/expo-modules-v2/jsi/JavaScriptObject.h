#pragma once

#include <jni.h>
#include <jsi/jsi.h>
#include <optional>

#include <kolibri/JavaClass.h>
#include <kolibri/native_method.h>
#include <kolibri/ScopedNativeObject.h>

namespace expo::modules::v2::jsi {
  class JavaScriptValue;

  class JavaScriptObject : public kolibri::ScopedNativeObject<JavaScriptObject> {
    friend ScopedNativeObject;

  public:
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/jsi/JavaScriptObject";

    static constexpr Constructor<void(jlong)> constructor{};

    JavaScriptObject(facebook::jsi::Runtime* runtime, facebook::jsi::Object object);

    ~JavaScriptObject() override;

    static void registerNatives(JNIEnv* env);

    static jobject create(
      JNIEnv* env,
      facebook::jsi::Runtime* runtime,
      facebook::jsi::Object object
    );

    [[nodiscard]] const facebook::jsi::Object& object() const;

    [[nodiscard]] facebook::jsi::Runtime& runtime() const;

  private:
    // clang-format off
    jboolean isArray() const noexcept;
    jboolean isArrayBuffer() const noexcept;
    jboolean hasProperty(JNIEnv* env, jstring name) const;
    jobject getProperty(JNIEnv* env, jstring name) const;
    jobjectArray getPropertyNames(JNIEnv* env) const;
    jobjectArray getArray(JNIEnv* env) const;
    void setBoolProperty(JNIEnv* env, jstring name, jboolean value) const;
    void setDoubleProperty(JNIEnv* env, jstring name, jdouble value) const;
    void setStringProperty(JNIEnv* env, jstring name, jstring value) const;
    void setJSValueProperty(JNIEnv* env, jstring name, JavaScriptValue* value) const;
    void setObjectProperty(JNIEnv* env, jstring name, JavaScriptObject* value) const;
    void unsetProperty(JNIEnv* env, jstring name) const;
    jobject nativeInstance(JNIEnv* env) const;
    // clang-format on

    void onScopeInvalidated() { object_.reset(); }

    facebook::jsi::Runtime* runtime_;
    std::optional<facebook::jsi::Object> object_;
  };
} // namespace expo::modules::v2::jsi
