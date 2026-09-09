#pragma once

#include <optional>
#include <string>
#include <unordered_map>

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/Ref.h>

#include <expo-modules-v2/jni/JAsyncContext.h>
#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

namespace expo::modules::v2::async {
  class AsyncRuntimeState {
  public:
    AsyncRuntimeState(JNIEnv* env, facebook::jsi::Runtime& rt, jobject asyncContext);

    ~AsyncRuntimeState();

    AsyncRuntimeState(const AsyncRuntimeState&) = delete;

    AsyncRuntimeState& operator=(const AsyncRuntimeState&) = delete;

    [[nodiscard]] static AsyncRuntimeState* find(const facebook::jsi::Runtime& rt) noexcept;

    /** The Kotlin `AsyncContext` of this runtime, or null once it was invalidated. */
    [[nodiscard]] jobject context() const;

    [[nodiscard]] uint64_t beginCall(ExpectedType returnType);

    [[nodiscard]] facebook::jsi::Value finishCall(facebook::jsi::Runtime& rt, uint64_t id);

    [[nodiscard]] kolibri::Ref<JPromise> newKotlinPromise(JNIEnv* env, uint64_t id) const;

    /**
     * Settles whatever the trampoline just produced without suspending.
     */
    void drainInlineSettles(JNIEnv* env) const;

    /** Drops a pending entry without settling it, for a call that failed before it ever started. */
    void discard(uint64_t id) noexcept;

    /** The result arrived in a JNI slot. */
    void resolveFromJni(facebook::jsi::Runtime& rt, JNIEnv* env, uint64_t id, jobject value);

    /** The result arrived on the shared binary buffer, [payloadLength] bytes of it. */
    void resolveFromBuffer(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      uint64_t id,
      jint payloadLength
    );

    void reject(
      facebook::jsi::Runtime& rt,
      uint64_t id,
      const std::string& code,
      const std::string& message,
      const std::string& stack
    );

  private:
    struct Deferred {
      facebook::jsi::Function resolveFn;
      facebook::jsi::Function rejectFn;
    };

    struct Pending {
      ExpectedType returnType;
      std::optional<Deferred> deferred;
      std::optional<facebook::jsi::Value> outcome;
      bool rejected = false;
    };

    struct JSPromise {
      facebook::jsi::Value promise;
      facebook::jsi::Function resolveFn;
      facebook::jsi::Function rejectFn;
    };

    void settle(uint64_t id, facebook::jsi::Value&& value, bool rejected);

    JSPromise createJSPromise(
      facebook::jsi::Runtime& rt
    );

    facebook::jsi::Value callPromiseResolve(
      facebook::jsi::Runtime& rt,
      facebook::jsi::Value result
    );

    facebook::jsi::Value callPromiseReject(
      facebook::jsi::Runtime& rt,
      facebook::jsi::Value result
    );

    facebook::jsi::Runtime* runtime_;
    kolibri::GlobalRef<JAsyncContext> jContext_;

    std::optional<facebook::jsi::Function> factory_;

    std::optional<facebook::jsi::Object> promiseClass_;
    std::optional<facebook::jsi::Function> promiseResolve_;
    std::optional<facebook::jsi::Function> promiseReject_;

    void ensureFactory(facebook::jsi::Runtime& rt);

    void ensureSettledFactories(facebook::jsi::Runtime& rt);

    std::unordered_map<uint64_t, Pending> pending_;
    uint64_t nextId_ = 1;
  };
} // namespace expo::modules::v2::async
