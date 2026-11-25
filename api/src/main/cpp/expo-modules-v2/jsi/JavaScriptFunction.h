#pragma once

#include <memory>
#include <optional>
#include <string_view>

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/JavaClass.h>
#include <kolibri/array.h>
#include <kolibri/Ref.h>
#include <kolibri/members/Method.h>
#include <kolibri/ScopedNativeObject.h>

#include <expo-modules-v2/descriptor/FunctionSignature.h>

namespace expo::modules::v2 {
  struct JFunctionCallback : kolibri::JavaClass<JFunctionCallback> {
    static constexpr std::string_view descriptor =
      "io/github/expo/modules/v2/jsi/JavaScriptFunctionCallback";

    static constexpr Method<"resolve", void(jobject)> resolve{};
    static constexpr Method<"reject", void(jstring, jstring, jstring)> reject{};

    struct Accessors : BaseAccessors {
      JAVA_FORWARD_ACCESSOR(resolve)
      JAVA_FORWARD_ACCESSOR(reject)
    };
  };
} // namespace expo::modules::v2

namespace expo::modules::v2::jsi {
  class JavaScriptFunction : public kolibri::ScopedNativeObject<JavaScriptFunction> {
    friend ScopedNativeObject;

  public:
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/jsi/JavaScriptFunction";

    // The pointer, and the Kotlin `AsyncContext` of the runtime the function belongs to.
    static constexpr Constructor<void(jlong, jobject)> constructor{};

    JavaScriptFunction(
      facebook::jsi::Runtime* runtime,
      facebook::jsi::Function function,
      std::shared_ptr<const FunctionSignature> signature
    );

    ~JavaScriptFunction() override;

    static void registerNatives(JNIEnv* env);

    static jobject create(
      JNIEnv* env,
      facebook::jsi::Runtime* runtime,
      facebook::jsi::Function function,
      std::shared_ptr<const FunctionSignature> signature
    );

  private:
    void call(
      JNIEnv* env,
      kolibri::UnownedRef<kolibri::JObjectArray> args,
      jobject callback,
      jboolean settleNow
    ) const;

    void onScopeInvalidated() { function_.reset(); }

    facebook::jsi::Runtime* runtime_;
    std::optional<facebook::jsi::Function> function_;
    std::shared_ptr<const FunctionSignature> signature_;
  };
} // namespace expo::modules::v2::jsi
