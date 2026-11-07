#pragma once

#include <memory>
#include <optional>
#include <string_view>

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/members/Method.h>
#include <kolibri/native_method.h>
#include <kolibri/ScopedNativeObject.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  /** `io.github.expo.modules.v2.jsi.JavaScriptFunctionCallback`: where a call reports its outcome. */
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
  /**
   * A JavaScript function that Kotlin holds as a lambda.
   *
   * It keeps the signature it was declared with, so a call converts Kotlin's arguments and the
   * function's result without being told the types again. Everything here runs on the JS thread;
   * the Kotlin side checks that before it gets this far.
   */
  class JavaScriptFunction : public kolibri::ScopedNativeObject<JavaScriptFunction> {
    friend ScopedNativeObject;

  public:
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/jsi/JavaScriptFunction";

    static constexpr Constructor<void(jlong)> constructor{};

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
    /**
     * Calls the function with [args] (already in their bridge form) and reports to [callback]:
     * `resolve` with the result in the bridge form of the declared return type, or `reject` with
     * what JavaScript threw. When [awaitPromise] is set and the function returns a promise, the
     * report waits for it to settle.
     */
    void call(JNIEnv* env, jobjectArray args, jobject callback, jboolean awaitPromise) const;

    void onScopeInvalidated() { function_.reset(); }

    facebook::jsi::Runtime* runtime_;
    std::optional<facebook::jsi::Function> function_;
    std::shared_ptr<const FunctionSignature> signature_;
  };
} // namespace expo::modules::v2::jsi
