#include "AttachedRuntime.h"

#include <kolibri/string_utils.h>

namespace expo::modules::v2::jsi {
  AttachedRuntime::AttachedRuntime(
    JNIEnv* env,
    jlong runtimePointer,
    jobject registry,
    jobject asyncContext,
    jstring engineName,
    jstring globalName
  ) : JavaScriptRuntime(
        env,
        registry,
        asyncContext,
        *reinterpret_cast<facebook::jsi::Runtime*>(runtimePointer),
        kolibri::toStdString(env, engineName),
        kolibri::toStdString(env, globalName)
      ) {}

  // A static native that mints the pointer, so there is nothing to recover (hence it is bound as a
  // plain function pointer). The address is cast through the base so it round-trips unchanged when
  // the base's instance methods recover their receiver from the leading `nativePointer` argument.
  jlong AttachedRuntime::nativeCreate(
    JNIEnv* env,
    jlong runtimePointer,
    jobject registry,
    jobject asyncContext,
    jstring engineName,
    jstring globalName
  ) {
    return reinterpret_cast<jlong>(
      static_cast<JavaScriptRuntime*>(
        new AttachedRuntime(env, runtimePointer, registry, asyncContext, engineName, globalName)
      )
    );
  }

  void AttachedRuntime::registerNatives(JNIEnv* env) {
    // Explicit signature: Kotlin declares the parameters as ModuleRegistry/AsyncContext, while the
    // C++ target takes plain jobjects (which would derive as Ljava/lang/Object;).
    kolibri::registerNative<AttachedRuntime>(env)
      .method<&AttachedRuntime::nativeCreate>(
        "nativeCreate",
        "(JLexpo/modules/v2/modules/ModuleRegistry;Lexpo/modules/v2/async/AsyncContext;"
        "Ljava/lang/String;Ljava/lang/String;)J"
      )
      .commit();
  }
} // namespace expo::modules::v2::jsi
