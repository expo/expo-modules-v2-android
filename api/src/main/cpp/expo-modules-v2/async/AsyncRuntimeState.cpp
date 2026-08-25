#include <expo-modules-v2/async/AsyncRuntimeState.h>

#include <mutex>
#include <utility>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/binary/BinaryReader.h>
#include <kolibri/env.h>

#include <expo-modules-v2/converter/decoders/BufferDecode.h>
#include <expo-modules-v2/jni/JTrampoline.h>
#include <expo-modules-v2/converter/decoders/JniDecode.h>

namespace expo::modules::v2::async {
  namespace {
    /**
     * A host function has only a `jsi::Runtime&`, so the state has to be reachable from one.
     */
    std::mutex& registryMutex() {
      static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<const facebook::jsi::Runtime*, AsyncRuntimeState*>& registry() {
      static std::unordered_map<const facebook::jsi::Runtime*, AsyncRuntimeState*> states;
      return states;
    }

    /**
     * `() => [promise, resolve, reject]`.
     *
     * Built from JavaScript rather than from `new Promise(hostFunctionExecutor)` on purpose.
     * This closure captures nothing on the C++ side and allocates less per call.
     */
    facebook::jsi::Function makeDeferredFactory(facebook::jsi::Runtime& rt) {
      static constexpr auto kSource =
        "(function () {"
        "  return function () {"
        "    let res, rej;"
        "    const p = new Promise(function (a, b) { res = a; rej = b; });"
        "    return [p, res, rej];"
        "  };"
        "})()";

      return rt.evaluateJavaScript(
        std::make_shared<facebook::jsi::StringBuffer>(kSource),
        "expo-modules-v2/deferred.js"
      ).getObject(rt).getFunction(rt);
    }
  } // namespace

  AsyncRuntimeState::AsyncRuntimeState(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject asyncContext
  ) : runtime_(&rt),
      jContext_(kolibri::GlobalRef<JAsyncContext>::make(env, asyncContext)) {
    std::lock_guard lock{registryMutex()};
    registry()[&rt] = this;
  }

  AsyncRuntimeState::~AsyncRuntimeState() {
    {
      std::lock_guard lock{registryMutex()};
      registry().erase(runtime_);
    }

    // Cut Kotlin's route back in first: after this, a settle that was already in flight finds a
    // zero runtime pointer and returns, so nothing can reach the entries being dropped below.
    if (jContext_) {
      JNIEnv* env = kolibri::getEnv();
      jContext_->invalidate(env);
      jContext_.reset();
    }

    // Destroys every pending resolve/reject. Runs wherever the runtime is destroyed, which must be
    // the JS thread - the same requirement the rest of the runtime's teardown already has.
    pending_.clear();
    factory_.reset();
    promiseResolve_.reset();
    promiseReject_.reset();
    promiseClass_.reset();
  }

  AsyncRuntimeState* AsyncRuntimeState::find(const facebook::jsi::Runtime& rt) noexcept {
    // TODO(@lukmccall): can we drop lock_guard?
    std::lock_guard lock{registryMutex()};
    const auto found = registry().find(&rt);
    return found == registry().end() ? nullptr : found->second;
  }

  void AsyncRuntimeState::ensureFactory(facebook::jsi::Runtime& rt) {
    if (!factory_.has_value()) {
      factory_.emplace(makeDeferredFactory(rt));
    }
  }

  void AsyncRuntimeState::ensureSettledFactories(facebook::jsi::Runtime& rt) {
    if (promiseResolve_.has_value()) {
      return;
    }
    facebook::jsi::Object promiseClass = rt.global().getPropertyAsObject(rt, "Promise");
    promiseResolve_.emplace(promiseClass.getPropertyAsFunction(rt, "resolve"));
    promiseReject_.emplace(promiseClass.getPropertyAsFunction(rt, "reject"));
    // Last, because `Promise.resolve` needs it as the receiver and moving it invalidates nothing else.
    promiseClass_.emplace(std::move(promiseClass));
  }

  uint64_t AsyncRuntimeState::beginCall(ExpectedType returnType) {
    const uint64_t id = nextId_++;
    pending_.emplace(
      id,
      Pending{
        .returnType = std::move(returnType),
        .deferred = std::nullopt,
        .outcome = std::nullopt
      }
    );
    return id;
  }

  facebook::jsi::Value AsyncRuntimeState::finishCall(
    facebook::jsi::Runtime& rt,
    const uint64_t id
  ) {
    const auto found = pending_.find(id);
    if (found == pending_.end()) [[unlikely]] {
      throw std::runtime_error("Unexpected error");
    }

    auto& pending = found->second;
    if (pending.outcome.has_value()) {
      facebook::jsi::Value outcome = std::move(*pending.outcome);
      pending_.erase(found);

      if (pending.rejected) [[unlikely]] {
        return callPromiseReject(rt, std::move(outcome));
      }

      return callPromiseResolve(rt, std::move(outcome));
    }

    auto [promise, resolveFn, rejectFn] = createJSPromise(rt);
    pending.deferred.emplace(
      Deferred{
        .resolveFn = std::move(resolveFn),
        .rejectFn = std::move(rejectFn),
      }
    );
    return std::move(promise);
  }

  kolibri::Ref<JPromise> AsyncRuntimeState::newKotlinPromise(JNIEnv* env, const uint64_t id) const {
    return jContext_->createPromise(env, id);
  }

  void AsyncRuntimeState::drainInlineSettles(JNIEnv* env) const {
    jContext_->drainInlineSettles(env);
  }

  void AsyncRuntimeState::discard(const uint64_t id) noexcept {
    pending_.erase(id);
  }

  void AsyncRuntimeState::settle(
    const uint64_t id,
    facebook::jsi::Value&& value,
    const bool rejected
  ) {
    const auto found = pending_.find(id);
    if (found == pending_.end()) [[unlikely]] {
      return;
    }

    auto& pending = found->second;
    if (!pending.deferred.has_value()) {
      // Still inside the call that started this. `finishCall` is a few instructions away and turns
      // the outcome into a promise that is born settled.
      pending.outcome.emplace(std::move(value));
      pending.rejected = rejected;
      return;
    }

    const Deferred deferred = std::move(*pending.deferred);
    facebook::jsi::Runtime& rt = *runtime_;
    pending_.erase(found);
    (rejected ? deferred.rejectFn : deferred.resolveFn).call(rt, value);
  }

  AsyncRuntimeState::JSPromise AsyncRuntimeState::createJSPromise(
    facebook::jsi::Runtime& rt
  ) {
    ensureFactory(rt);
    const facebook::jsi::Array triple = factory_->call(rt).getObject(rt).getArray(rt);

    return JSPromise{
      .promise = triple.getValueAtIndex(rt, 0),
      .resolveFn = triple.getValueAtIndex(rt, 1).getObject(rt).getFunction(rt),
      .rejectFn = triple.getValueAtIndex(rt, 2).getObject(rt).getFunction(rt),
    };
  }

  facebook::jsi::Value AsyncRuntimeState::callPromiseResolve(
    facebook::jsi::Runtime& rt,
    facebook::jsi::Value result
  ) {
    ensureSettledFactories(rt);
    return promiseResolve_->callWithThis(rt, *promiseClass_, std::move(result));
  }

  facebook::jsi::Value AsyncRuntimeState::callPromiseReject(facebook::jsi::Runtime& rt, facebook::jsi::Value result) {
    ensureSettledFactories(rt);
    return promiseReject_->callWithThis(rt, *promiseClass_, std::move(result));
  }

  void AsyncRuntimeState::resolveFromJni(
    facebook::jsi::Runtime& rt,
    JNIEnv* env,
    const uint64_t id,
    jobject value
  ) {
    const auto found = pending_.find(id);
    if (found == pending_.end()) [[unlikely]] {
      return;
    }

    settle(id, decodeFromJni(env, rt, value, found->second.returnType), false);
  }

  void AsyncRuntimeState::resolveFromBuffer(
    facebook::jsi::Runtime& rt,
    JNIEnv* env,
    const uint64_t id,
    const jint payloadLength
  ) {
    const auto found = pending_.find(id);
    if (found == pending_.end()) [[unlikely]] {
      return;
    }
    const ExpectedType& returnType = found->second.returnType;

    if (payloadLength == JTrampoline::kOverflowArgumentsSentinel) [[unlikely]] {
      const kolibri::Ref<> overflowed = JTrampoline::takeOverflowResult(env);
      settle(id, decodeFromJni(env, rt, overflowed.get(), returnType), false);
      return;
    }

    const kolibri::binary::BinaryBuffer::Claim claim;
    if (!claim) {
      throw std::runtime_error(
        "The binary bridge buffer is already in use while settling a promise - a job queue was "
        "drained from inside a host function"
      );
    }

    kolibri::binary::Reader reader{
      .position = claim.buffer().data(),
      .end = claim.buffer().data() + static_cast<size_t>(payloadLength)
    };
    settle(id, decodeFromBuffer(rt, reader, returnType), false);
  }

  void AsyncRuntimeState::reject(
    facebook::jsi::Runtime& rt,
    const uint64_t id,
    const std::string& code,
    const std::string& message,
    const std::string& stack
  ) {
    if (!pending_.contains(id)) {
      return;
    }

    facebook::jsi::Object error = rt.global()
      .getPropertyAsFunction(rt, "Error")
      .callAsConstructor(
        rt,
        facebook::jsi::String::createFromUtf8(rt, message)
      )
      .getObject(rt);
    error.setProperty(rt, "code", facebook::jsi::String::createFromUtf8(rt, code));
    if (!stack.empty()) {
      // `stack` is JavaScript's own; the Kotlin trace goes beside it under its own name.
      error.setProperty(rt, "nativeStack", facebook::jsi::String::createFromUtf8(rt, stack));
    }

    settle(id, facebook::jsi::Value(rt, error), true);
  }
} // namespace expo::modules::v2::async
