#include <expo-modules-v2/jni/JAsyncContext.h>

#include <string>

#include <kolibri/native_method.h>
#include <kolibri/string_utils.h>

#include <expo-modules-v2/async/AsyncRuntimeState.h>
#include <expo-modules-v2/jsi/JavaScriptRuntime.h>

namespace expo::modules::v2 {
  namespace {
    jsi::JavaScriptRuntime* runtimeFor(const jlong runtimePointer) noexcept {
      return reinterpret_cast<jsi::JavaScriptRuntime*>(runtimePointer);
    }

    void nativeResolveBuffered(
      JNIEnv* env,
      const jlong runtimePointer,
      const jlong id,
      const jint payloadLength
    ) {
      jsi::JavaScriptRuntime* runtime = runtimeFor(runtimePointer);
      if (runtime == nullptr) {
        return;
      }
      if (async::AsyncRuntimeState* state = runtime->asyncState()) {
        state->resolveFromBuffer(
          runtime->runtime(),
          env,
          static_cast<uint64_t>(id),
          payloadLength
        );
      }
    }

    void nativeResolve(JNIEnv* env, const jlong runtimePointer, const jlong id, jobject value) {
      jsi::JavaScriptRuntime* runtime = runtimeFor(runtimePointer);
      if (runtime == nullptr) {
        return;
      }
      if (async::AsyncRuntimeState* state = runtime->asyncState()) {
        state->resolveFromJni(runtime->runtime(), env, static_cast<uint64_t>(id), value);
      }
    }

    void nativeReject(
      JNIEnv* env,
      const jlong runtimePointer,
      const jlong id,
      jstring code,
      jstring message,
      jstring stack
    ) {
      jsi::JavaScriptRuntime* runtime = runtimeFor(runtimePointer);
      if (runtime == nullptr) {
        return;
      }
      if (async::AsyncRuntimeState* state = runtime->asyncState()) {
        state->reject(
          runtime->runtime(),
          static_cast<uint64_t>(id),
          kolibri::toStdString(env, code),
          kolibri::toStdString(env, message),
          stack != nullptr ? kolibri::toStdString(env, stack) : std::string{}
        );
      }
    }
  } // namespace

  void JAsyncContext::registerNatives(JNIEnv* env) {
    kolibri::registerNative<JAsyncContext>(env)
      .method<&nativeResolveBuffered>("nativeResolveBuffered", "(JJI)V")
      .method<&nativeResolve>("nativeResolve", "(JJLjava/lang/Object;)V")
      .method<&nativeReject>(
        "nativeReject",
        "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
      )
      .commit();
  }

  kolibri::Ref<JPromise> JAsyncContext::Accessors::createPromise(JNIEnv* env, const jlong id) const {
    return callToken(env, Owner::createPromise_, id);
  }

  void JAsyncContext::Accessors::invalidate(JNIEnv* env) const {
    callToken(env, Owner::invalidate_);
  }

  void JAsyncContext::Accessors::drainInlineSettles(JNIEnv* env) const {
    callToken(env, Owner::drainInlineSettles_);
  }
} // namespace expo::modules::v2
