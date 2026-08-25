#pragma once

#include <jni.h>
#include <string_view>

#include <expo-modules-v2/jsi/JavaScriptRuntime.h>

namespace expo::modules::v2::jsi {
  /**
   * A JavaScriptRuntime attached to a `jsi::Runtime` somebody else created, exposed to the JVM as
   * `expo.modules.v2.jsi.AttachedRuntime`.
   *
   * This is the only shape this library needs: it never creates a VM. The host does — React Native
   * in an app, `hermes-tests-environment` on the desktop and in an Android app without React
   * Native — and hands over the address. This object borrows it, installs the module host object
   * into its global, and never frees the runtime; only the C++ object that wraps it.
   *
   * Construct it on the host's JS thread: a `jsi::Runtime` is thread-affine, and installing the
   * host object touches the global object.
   */
  class AttachedRuntime : public JavaScriptRuntime {
  public:
    // The internal (binary) class name of the Kotlin handle; shadows the base's.
    static constexpr std::string_view descriptor = "expo/modules/v2/jsi/AttachedRuntime";

    // Registers `nativeCreate`. The inherited instance methods are registered against the Kotlin
    // base class by JNI_OnLoad. Call once from there.
    static void registerNatives(JNIEnv* env);

  private:
    AttachedRuntime(
      JNIEnv* env,
      jlong runtimePointer,
      jobject registry,
      jobject asyncContext,
      jstring engineName,
      jstring globalName
    );

    /**
     * The `nativeCreate` static native. [runtimePointer] is a `facebook::jsi::Runtime*` this object
     * borrows and never frees — the host that created it outlives this object.
     */
    static jlong nativeCreate(
      JNIEnv* env,
      jlong runtimePointer,
      jobject registry,
      jobject asyncContext,
      jstring engineName,
      jstring globalName
    );
  };
} // namespace expo::modules::v2::jsi
