#include <expo-modules-cpp/internal/PromiseTable.h>

#include <memory>
#include <stdexcept>
#include <utility>

#include <expo-modules-cpp/Errors.h>

namespace expo::modules::v2::cpp::detail {
  namespace {
    /**
     * `() => [promise, resolve, reject]`. Built from JavaScript rather than from `new
     * Promise(hostFunctionExecutor)`: the closure captures nothing on the C++ side, and each call
     * allocates less.
     */
    js::Function makeDeferredFactory(js::Runtime& rt) {
      static constexpr auto kSource =
        "(function () {"
        "  return function () {"
        "    let res, rej;"
        "    const p = new Promise(function (a, b) { res = a; rej = b; });"
        "    return [p, res, rej];"
        "  };"
        "})()";

      return rt.evaluateJavaScript(
        std::make_shared<js::StringBuffer>(kSource),
        "expo-modules-cpp/deferred.js"
      ).getObject(rt).getFunction(rt);
    }
  } // namespace

  uint64_t PromiseTable::begin() {
    const uint64_t id = nextId_++;
    pending_.emplace(id, Pending{});
    return id;
  }

  js::Value PromiseTable::finish(js::Runtime& rt, const uint64_t id) {
    const auto found = pending_.find(id);
    if (found == pending_.end()) [[unlikely]] {
      throw std::logic_error("A promise was finished twice");
    }

    Pending& pending = found->second;
    if (pending.outcome.has_value()) {
      js::Value outcome = std::move(*pending.outcome);
      const bool rejected = pending.rejected;
      pending_.erase(found);

      ensureSettledFunctions(rt);
      return (rejected ? *promiseReject_ : *promiseResolve_).callWithThis(rt, *promiseClass_, std::move(outcome));
    }

    if (!factory_.has_value()) {
      factory_.emplace(makeDeferredFactory(rt));
    }
    const js::Array triple = factory_->call(rt).getObject(rt).getArray(rt);
    pending.deferred.emplace(Deferred{
      .resolve = triple.getValueAtIndex(rt, 1).getObject(rt).getFunction(rt),
      .reject = triple.getValueAtIndex(rt, 2).getObject(rt).getFunction(rt),
    });
    return triple.getValueAtIndex(rt, 0);
  }

  void PromiseTable::settle(js::Runtime& rt, const uint64_t id, js::Value&& value, const bool rejected) {
    const auto found = pending_.find(id);
    if (found == pending_.end()) [[unlikely]] {
      return;
    }

    Pending& pending = found->second;
    if (!pending.deferred.has_value()) {
      // Still inside the call that began this: `finish` is a few instructions away and turns the
      // outcome into a promise that is born settled.
      pending.outcome.emplace(std::move(value));
      pending.rejected = rejected;
      return;
    }

    // Erased before the call: resolving runs no JavaScript of ours, but rejecting an already
    // settled promise's `then` chain may, and it must find the table consistent.
    const Deferred deferred = std::move(*pending.deferred);
    pending_.erase(found);
    (rejected ? deferred.reject : deferred.resolve).call(rt, value);
  }

  void PromiseTable::reject(js::Runtime& rt, const uint64_t id, std::exception_ptr error) {
    if (!pending_.contains(id)) {
      return;
    }
    js::Value value = [&] {
      try {
        std::rethrow_exception(std::move(error));
      } catch (...) {
        return currentExceptionToError(rt);
      }
    }();
    settle(rt, id, std::move(value), true);
  }

  void PromiseTable::ensureSettledFunctions(js::Runtime& rt) {
    if (promiseResolve_.has_value()) {
      return;
    }
    js::Object promiseClass = rt.global().getPropertyAsObject(rt, "Promise");
    promiseResolve_.emplace(promiseClass.getPropertyAsFunction(rt, "resolve"));
    promiseReject_.emplace(promiseClass.getPropertyAsFunction(rt, "reject"));
    // Last: `Promise.resolve` needs it as its receiver.
    promiseClass_.emplace(std::move(promiseClass));
  }

  void PromiseTable::clear() {
    pending_.clear();
    factory_.reset();
    promiseResolve_.reset();
    promiseReject_.reset();
    promiseClass_.reset();
  }
} // namespace expo::modules::v2::cpp::detail
