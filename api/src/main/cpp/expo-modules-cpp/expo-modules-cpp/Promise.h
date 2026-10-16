#pragma once

#include <atomic>
#include <cstdint>
#include <exception>
#include <memory>
#include <string>
#include <type_traits>
#include <utility>

#include <expo-modules-cpp/Converter.h>
#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/Errors.h>
#include <expo-modules-cpp/internal/RuntimeData.h>
#include <expo-modules-cpp/internal/RuntimeLink.h>

namespace expo::modules::v2::cpp::detail {
  struct PromiseAccess;

  /**
   * What every handle to one JavaScript promise shares: which runtime it belongs to, which entry of
   * that runtime's `PromiseTable` it settles, and whether something settled it already. Safe from
   * any thread.
   */
  class EXPO_API PromiseCore {
  public:
    PromiseCore(std::shared_ptr<RuntimeLink> link, const uint64_t id) : link_(std::move(link)), id_(id) {
    }

    /** Claims the right to settle. True for the first caller only. */
    [[nodiscard]] bool trySettle() noexcept { return !settled_.exchange(true, std::memory_order_acq_rel); }

    [[nodiscard]] bool isSettled() const noexcept { return settled_.load(std::memory_order_acquire); }

    [[nodiscard]] RuntimeLink& link() const noexcept { return *link_; }

    [[nodiscard]] uint64_t id() const noexcept { return id_; }

    /** Settles with [error] on the JS thread. */
    void rejectWith(std::exception_ptr error) const {
      link_->runOnJS([id = id_, error = std::move(error)](RuntimeData& data) {
        data.promises().reject(data.runtime(), id, error);
      });
    }

  private:
    std::shared_ptr<RuntimeLink> link_;
    uint64_t id_;
    std::atomic<bool> settled_{false};
  };
} // namespace expo::modules::v2::cpp::detail

namespace expo::modules::v2::cpp {
  template<typename T = void>
  class Promise;

  namespace detail {
    /** What a promise handle does whatever it carries: refuse a second settle, and reject. */
    class PromiseBase {
    public:
      /** Rejects with an `Error` that carries [code] and [message]. */
      void reject(std::string code, const std::string& message) const {
        reject(std::make_exception_ptr(Exception(std::move(code), message)));
      }

      /** Rejects with the exception, the way a throwing function is reported. */
      void reject(std::exception_ptr error) const {
        if (core_->trySettle()) {
          core_->rejectWith(std::move(error));
        }
      }

      template<typename E>
        requires(std::is_base_of_v<std::exception, E>)
      void reject(const E& error) const {
        reject(std::make_exception_ptr(error));
      }

      /** Whether something already settled the promise. */
      [[nodiscard]] bool isSettled() const noexcept { return core_->isSettled(); }

    protected:
      explicit PromiseBase(std::shared_ptr<PromiseCore> core) : core_(std::move(core)) {
      }

      std::shared_ptr<PromiseCore> core_;
    };
  } // namespace detail

  /**
   * The settling side of a JavaScript promise: Kotlin's `Promise`. An exported function that takes
   * one as its last parameter returns a promise to JavaScript, and settles it - now, or later from
   * any thread - through this.
   *
   * ```cpp
   * def.function("load", [](std::string url, cpp::Promise<std::string> promise) {
   *   pool.submit([=] {
   *     try {
   *       promise.resolve(download(url));
   *     } catch (const std::exception& error) {
   *       promise.reject("ERR_DOWNLOAD", error.what());
   *     }
   *   });
   * });
   * ```
   *
   * A promise settles once: the first [resolve] or [reject] counts and the rest are ignored. A
   * promise nobody settles leaves JavaScript waiting forever. The value is converted on the JS
   * thread, so it must not change after it was handed over; a value that does not convert rejects
   * the promise with the conversion's error. Copies of a handle share the one promise.
   */
  template<typename T>
  class Promise : public detail::PromiseBase {
  public:
    void resolve(T value) const {
      if (!core_->trySettle()) {
        return;
      }
      core_->link().runOnJS([id = core_->id(), value = std::make_shared<T>(std::move(value))](detail::RuntimeData& data) {
        data.promises().resolveWith(data.runtime(), id, [&value](js::Runtime& rt) {
          return Converter<T>::toJS(rt, *value);
        });
      });
    }

  private:
    friend struct detail::PromiseAccess;

    explicit Promise(std::shared_ptr<detail::PromiseCore> core) : PromiseBase(std::move(core)) {
    }
  };

  /** A promise that carries no value: JavaScript sees `undefined`. */
  template<>
  class Promise<void> : public detail::PromiseBase {
  public:
    void resolve() const {
      if (!core_->trySettle()) {
        return;
      }
      core_->link().runOnJS([id = core_->id()](detail::RuntimeData& data) {
        data.promises().resolveWith(data.runtime(), id, [](js::Runtime&) { return js::Value::undefined(); });
      });
    }

  private:
    friend struct detail::PromiseAccess;

    explicit Promise(std::shared_ptr<detail::PromiseCore> core) : PromiseBase(std::move(core)) {
    }
  };
} // namespace expo::modules::v2::cpp

namespace expo::modules::v2::cpp::detail {
  /** How the library makes a `Promise` from a core, which the public constructor does not allow. */
  struct PromiseAccess {
    template<typename T>
    static Promise<T> make(std::shared_ptr<PromiseCore> core) {
      return Promise<T>(std::move(core));
    }
  };

  template<typename T>
  inline constexpr bool kIsPromise = kIsSpecialization<T, Promise>;
} // namespace expo::modules::v2::cpp::detail
