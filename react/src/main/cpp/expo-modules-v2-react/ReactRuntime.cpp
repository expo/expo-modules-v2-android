#include "ReactRuntime.h"

#include <kolibri/string_utils.h>

namespace expo::modules::v2::react {
  ReactRuntime::ReactRuntime(
    JNIEnv* env,
    jlong runtimePointer,
    jobject registry,
    jobject asyncContext,
    jstring globalName
  ) : ::expo::modules::v2::jsi::JavaScriptRuntime(
        env,
        registry,
        asyncContext,
        *reinterpret_cast<facebook::jsi::Runtime*>(runtimePointer),
        "react-native",
        kolibri::toStdString(env, globalName)
      ) {}

  // Mirrors HermesRuntime::nativeCreate: a static native that mints the pointer, cast through the
  // base so it round-trips unchanged when the base's instance methods recover their receiver.
  jlong ReactRuntime::nativeCreate(
    JNIEnv* env,
    jlong runtimePointer,
    jobject registry,
    jobject asyncContext,
    jstring globalName
  ) {
    return reinterpret_cast<jlong>(
      static_cast<::expo::modules::v2::jsi::JavaScriptRuntime*>(
        new ReactRuntime(env, runtimePointer, registry, asyncContext, globalName)
      )
    );
  }

  void ReactRuntime::registerNatives(JNIEnv* env) {
    // Explicit signature: Kotlin declares the parameters as ModuleRegistry/AsyncContext, while the
    // C++ target takes plain jobjects (which would derive as Ljava/lang/Object;).
    kolibri::registerNative<ReactRuntime>(env)
      .method<&ReactRuntime::nativeCreate>(
        "nativeCreate",
        "(JLexpo/modules/v2/modules/ModuleRegistry;Lexpo/modules/v2/async/AsyncContext;"
        "Ljava/lang/String;)J"
      )
      .commit();
  }
} // namespace expo::modules::v2::react
