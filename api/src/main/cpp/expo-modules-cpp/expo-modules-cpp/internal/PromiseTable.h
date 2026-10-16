#pragma once

#include <cstdint>
#include <exception>
#include <optional>
#include <unordered_map>

#include <expo-modules-cpp/Defines.h>

namespace expo::modules::v2::cpp::detail {
  /**
   * The JavaScript promises a runtime handed out that native code has yet to settle. It holds
   * `js::Function`s, so it belongs to the JS thread and to the runtime: `RuntimeData` owns it.
   *
   * A call begins an entry, runs the exported callable, and finishes the entry into the promise
   * JavaScript receives. A callable that settles before it returns - which is every export that
   * does not really wait - is finished into a promise born settled, so the common case never makes
   * a resolve and a reject function.
   */
  class PromiseTable {
  public:
    PromiseTable() = default;

    PromiseTable(const PromiseTable&) = delete;
    PromiseTable& operator=(const PromiseTable&) = delete;

    /** Registers a pending promise and returns the id its settling will name. */
    [[nodiscard]] uint64_t begin();

    /** The promise JavaScript receives for [id]: pending, or already settled. */
    [[nodiscard]] js::Value finish(js::Runtime& rt, uint64_t id);

    /**
     * Resolves [id] with whatever [make] builds from the runtime. When [make] throws - the result
     * would not convert - the promise is rejected with that failure instead, so JavaScript still sees
     * exactly one settled promise.
     */
    template<typename MakeValue>
    void resolveWith(js::Runtime& rt, const uint64_t id, MakeValue&& make) {
      if (!pending_.contains(id)) {
        return;
      }
      try {
        settle(rt, id, make(rt), false);
      } catch (...) {
        reject(rt, id, std::current_exception());
      }
    }

    /** Rejects [id] with an `Error` made from [error], the way a throwing export is reported. */
    void reject(js::Runtime& rt, uint64_t id, std::exception_ptr error);

    /** Drops an entry that will never be finished, for a call that failed before it started. */
    void discard(uint64_t id) noexcept { pending_.erase(id); }

    /** Forgets every pending promise. Must run while the runtime is alive, on its thread. */
    void clear();

  private:
    struct Deferred {
      js::Function resolve;
      js::Function reject;
    };

    struct Pending {
      std::optional<Deferred> deferred;
      std::optional<js::Value> outcome;
      bool rejected = false;
    };

    void settle(js::Runtime& rt, uint64_t id, js::Value&& value, bool rejected);

    void ensureSettledFunctions(js::Runtime& rt);

    std::unordered_map<uint64_t, Pending> pending_;
    uint64_t nextId_ = 1;

    /** `() => [promise, resolve, reject]`, built once. */
    std::optional<js::Function> factory_;
    std::optional<js::Object> promiseClass_;
    std::optional<js::Function> promiseResolve_;
    std::optional<js::Function> promiseReject_;
  };
} // namespace expo::modules::v2::cpp::detail
