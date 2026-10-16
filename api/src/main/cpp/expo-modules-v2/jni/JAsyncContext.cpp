#include <expo-modules-v2/jni/JAsyncContext.h>

#include <string>

#include <kolibri/native_method.h>
#include <kolibri/string_utils.h>

#include <expo-modules-v2/async/AsyncRuntimeState.h>
#ifdef EXPO_MODULES_V2_CPP_API
#include <expo-modules-v2/async/JniScheduler.h>
#endif
#include <expo-modules-v2/jsi/JavaScriptRuntime.h>

namespace expo::modules::v2 {
  namespace {
    jsi::JavaScriptRuntime* runtimeFor(const jlong runtimePointer) noexcept {
      return reinterpret_cast<jsi::JavaScriptRuntime*>(runtimePointer);
    }

    void nativeRunJob(JNIEnv*, [[maybe_unused]] const jlong id) {
#ifdef EXPO_MODULES_V2_CPP_API
      async::JniScheduler::run(static_cast<uint64_t>(id));
#endif
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

    void nativeResolveNumber(
      JNIEnv*,
      const jlong runtimePointer,
      const jlong id,
      const jdouble value
    ) {
      jsi::JavaScriptRuntime* runtime = runtimeFor(runtimePointer);
      if (runtime == nullptr) {
        return;
      }
      if (async::AsyncRuntimeState* state = runtime->asyncState()) {
        state->resolveWithValue(static_cast<uint64_t>(id), facebook::jsi::Value(value));
      }
    }

    void nativeResolveBoolean(
      JNIEnv*,
      const jlong runtimePointer,
      const jlong id,
      const jboolean value
    ) {
      jsi::JavaScriptRuntime* runtime = runtimeFor(runtimePointer);
      if (runtime == nullptr) {
        return;
      }
      if (async::AsyncRuntimeState* state = runtime->asyncState()) {
        state->resolveWithValue(static_cast<uint64_t>(id), facebook::jsi::Value(value != JNI_FALSE));
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
      .method<&nativeRunJob>("nativeRunJob", "(J)V")
      .method<&nativeResolveBuffered>("nativeResolveBuffered", "(JJI)V")
      .method<&nativeResolve>("nativeResolve", "(JJLjava/lang/Object;)V")
      .method<&nativeResolveNumber>("nativeResolveNumber", "(JJD)V")
      .method<&nativeResolveBoolean>("nativeResolveBoolean", "(JJZ)V")
      .method<&nativeReject>(
        "nativeReject",
        "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
      )
      .commit();
  }

  kolibri::Ref<JPromiseHandle> JAsyncContext::Accessors::createPromise(JNIEnv* env, const jlong id) const {
    return callToken(env, Owner::createPromise_, id);
  }

  void JAsyncContext::Accessors::invalidate(JNIEnv* env) const {
    callToken(env, Owner::invalidate_);
  }

  void JAsyncContext::Accessors::drainInlineSettles(JNIEnv* env) const {
    callToken(env, Owner::drainInlineSettles_);
  }

  bool JAsyncContext::Accessors::isOnJSThread(JNIEnv* env) const {
    return callToken(env, Owner::isOnJSThread_) != JNI_FALSE;
  }

  void JAsyncContext::Accessors::postNative(JNIEnv* env, const jlong id) const {
    callToken(env, Owner::postNative_, id);
  }
} // namespace expo::modules::v2
