#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/JavaClass.h>
#include <kolibri/native_method.h>
#include <kolibri/ScopedNativeObject.h>

namespace expo::modules::v2::jsi {
  class JavaScriptValue : public kolibri::ScopedNativeObject<JavaScriptValue> {
    friend ScopedNativeObject;

  public:
    enum class Kind : jint {
      Undefined = 0,
      Null = 1,
      Boolean = 2,
      BigInt = 3,
      Number = 4,
      String = 5,
      Symbol = 6,
      Object = 7,
    };

    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/jsi/JavaScriptValue";

    static constexpr Constructor<void(jlong)> constructor{};

    static void registerNatives(JNIEnv* env);

    static jobject create(
      JNIEnv* env,
      facebook::jsi::Runtime* runtime,
      facebook::jsi::Value value
    );

    JavaScriptValue(facebook::jsi::Runtime* runtime, facebook::jsi::Value value);

    ~JavaScriptValue() override;

    [[nodiscard]] const facebook::jsi::Value& value() const;

    [[nodiscard]] facebook::jsi::Runtime& runtime() const;

    [[nodiscard]] Kind kind() const noexcept;

  private:
    // clang-format off
    jint kindCode() const noexcept;
    jboolean isNull() const noexcept;
    jboolean isUndefined() const noexcept;
    jboolean isBool() const noexcept;
    jboolean isNumber() const noexcept;
    jboolean isString() const noexcept;
    jboolean isSymbol() const noexcept;
    jboolean isFunction() const noexcept;
    jboolean isArray() const noexcept;
    jboolean isObject() const noexcept;
    jboolean getBool() const;
    jdouble getDouble() const;
    jstring getString(JNIEnv* env) const;
    jobject getObject(JNIEnv* env) const;
    jobjectArray getArray(JNIEnv* env) const;
    // clang-format on

    void onScopeInvalidated();

    facebook::jsi::Runtime* runtime_;
    facebook::jsi::Value value_;
  };
} // namespace expo::modules::v2::jsi
