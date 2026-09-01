#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/env.h>
#include <kolibri/native_method.h>

#include <expo-modules-v2/jsi/JavaScriptRuntime.h>
#include "TestSupportObject.h"

namespace {
  /**
   * Token for the Kotlin entry point (io.github.expo.modules.v2.testsupport.TestSupport). `nativeInstall`
   * receives the runtime handle's `nativePointer` — the address of the C++
   * `expo::modules::v2::jsi::JavaScriptRuntime` minted by the engine's `nativeCreate` — and installs
   * the `globalThis.ExpoTestSupport` host-function suite into that runtime.
   */
  struct JTestSupport : expo::kolibri::JavaClass<JTestSupport> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/testsupport/TestSupport";

    static void registerNatives(JNIEnv* env) {
      JavaClass::registerNatives(env)
        .method(
          "nativeInstall",
          [](jlong runtimePointer) {
            auto* runtime =
                reinterpret_cast<expo::modules::v2::jsi::JavaScriptRuntime*>(runtimePointer);
            expo::modules::v2::installTestSupport(runtime->runtime());
          }
        )
        .commit();
    }
  };
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  // The shared kolibri state lives in libexpo-kolibri.dylib, whose own JNI_OnLoad already ran
  // (TestSupport loads it first via ExpoModulesV2.ensureLoaded); initVM is a bare pointer
  // assignment, so repeating it here is harmless.
  expo::kolibri::initVM(vm);
  JNIEnv* env = expo::kolibri::getEnv();
  JTestSupport::registerNatives(env);
  return JNI_VERSION_1_6;
}
