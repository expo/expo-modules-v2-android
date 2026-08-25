#pragma once

#include <jni.h>
#include <string_view>

#include <expo-modules-v2/jsi/JavaScriptRuntime.h>
#include <kolibri/native_method.h>

namespace expo::modules::v2::react {
  /**
   * A JavaScriptRuntime attached to the `jsi::Runtime` React Native already created, exposed to the
   * JVM as `expo.modules.v2.react.ReactRuntime`.
   *
   * The desktop shape (`:hermes`) mints a VM and owns it. Here the host owns it: the app hands over
   * the address it read from `ReactContext.javaScriptContextHolder`, and this object only installs
   * the module host object into that runtime's global. Everything else — conversion, dispatch,
   * promises — is the engine-agnostic base from `:api`.
   */
  class ReactRuntime : public ::expo::modules::v2::jsi::JavaScriptRuntime {
  public:
    // The internal (binary) class name of the Kotlin handle; shadows the base's.
    static constexpr std::string_view descriptor = "expo/modules/v2/react/ReactRuntime";

    // Registers `nativeCreate`. The inherited instance methods are registered against the Kotlin
    // base class by this library's JNI_OnLoad. Call once from there.
    static void registerNatives(JNIEnv* env);

  private:
    ReactRuntime(
      JNIEnv* env,
      jlong runtimePointer,
      jobject registry,
      jobject asyncContext,
      jstring globalName
    );

    /**
     * The `nativeCreate` static native. [runtimePointer] is a `facebook::jsi::Runtime*` this object
     * borrows and never frees — React Native outlives it.
     */
    static jlong nativeCreate(
      JNIEnv* env,
      jlong runtimePointer,
      jobject registry,
      jobject asyncContext,
      jstring globalName
    );
  };
} // namespace expo::modules::v2::react
