#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <tuple>
#include <type_traits>
#include <utility>
#include <vector>

#include <expo-modules-cpp/Converter.h>
#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>
#include <expo-modules-cpp/internal/RuntimeData.h>
#include <expo-modules-cpp/internal/RuntimeLink.h>

namespace expo::modules::v2::cpp {
  /**
   * What an event tells its owner about JavaScript's interest in it. Both run on the thread that
   * caused the change, which is normally the JS thread of the runtime whose listener came or went.
   */
  struct EventHooks {
    /** The first listener, in any runtime, subscribed. */
    std::function<void()> onStartObserving;
    /**
     * The last listener, in every runtime, unsubscribed - or the runtime that held it was torn
     * down, or the object it listened to was released.
     */
    std::function<void()> onStopObserving;
  };

  namespace detail {
    /** The part of an event that does not depend on its payload. */
    class EXPO_API EventBase {
    public:
      /**
       * Declares an event of [owner]. Events are numbered in the order they are constructed, which is
       * how a runtime refers to them; two events of one object may not share a name.
       */
      EventBase(NativeObject* owner, std::string name, EventHooks hooks);

      virtual ~EventBase();

      EventBase(const EventBase&) = delete;
      EventBase& operator=(const EventBase&) = delete;

      /** The name JavaScript subscribes to. */
      [[nodiscard]] const std::string& name() const noexcept { return name_; }

      [[nodiscard]] uint32_t index() const noexcept { return index_; }

      [[nodiscard]] NativeObject& owner() const noexcept { return *owner_; }

      /** Whether any runtime currently has a listener for this event. */
      [[nodiscard]] bool isObserved() const noexcept { return observed_.load(std::memory_order_acquire) != 0; }

      /** [link]'s runtime gained its first listener for this event. */
      void attach(const std::shared_ptr<RuntimeLink>& link);

      /** [link]'s runtime lost its last listener for this event. */
      void detach(const RuntimeLink* link);

      /** Every runtime stops observing: the owner was released. */
      void detachAll();

    protected:
      /** The runtimes that listen right now. Taken under the lock; emitting is the hot path. */
      [[nodiscard]] std::vector<std::shared_ptr<RuntimeLink>> observers() const;

    private:
      NativeObject* owner_;
      std::string name_;
      uint32_t index_;
      EventHooks hooks_;

      mutable std::mutex mutex_;
      std::vector<std::shared_ptr<RuntimeLink>> observers_;
      std::atomic<uint32_t> observed_{0};
    };
  } // namespace detail

  /**
   * An event a module or shared object can emit to JavaScript, which subscribes with
   * `addListener(name, listener)`: Kotlin's `Event`.
   *
   * ```cpp
   * class Player : public cpp::SharedObject {
   * public:
   *   cpp::Event<std::string> onStateChange{this, "stateChange"};
   *
   *   void play() { onStateChange.emit("playing"); }
   * };
   * ```
   *
   * An event is a member of the object that owns it, constructed with `this` and the name
   * JavaScript uses. The payload is whatever [Args] convert to: each one becomes one argument of
   * the listener, so `Event<>` has none and `Event<int, std::string>` has two.
   *
   * [emit] is safe from any thread. Delivery is synchronous when the caller already is on the JS
   * thread of a listening runtime, and posted to it otherwise. The payload is converted once per
   * runtime that listens, on its JS thread, so it must not change after it was handed over.
   */
  template<typename... Args>
  class Event final : public detail::EventBase {
    static_assert(
      (... && !std::is_reference_v<Args>),
      "An event's payload is copied into the job that delivers it, possibly after emit() returned: "
      "declare Event<std::string>, not Event<const std::string&>"
    );

  public:
    Event(NativeObject* owner, std::string name, EventHooks hooks = {})
      : EventBase(owner, std::move(name), std::move(hooks)) {
    }

    void emit(Args... args) const {
      // Nobody listens: the common case, and it costs one load.
      if (!isObserved()) {
        return;
      }
      const std::vector<std::shared_ptr<detail::RuntimeLink>> targets = observers();
      if (targets.empty()) {
        return;
      }

      auto payload = std::make_shared<const std::tuple<Args...>>(std::move(args)...);
      for (const std::shared_ptr<detail::RuntimeLink>& link: targets) {
        link->runOnJS([id = owner().objectId(), index = index(), payload](detail::RuntimeData& data) {
          data.emit(id, index, [&payload](js::Runtime& rt) {
            return std::apply(
              // `[&]`, not `[&rt]`: an event without a payload has no value to convert, and clang
              // reports a capture nothing uses.
              [&](const Args&... values) {
                return std::array<js::Value, sizeof...(Args)>{Converter<std::decay_t<Args>>::toJS(rt, values)...};
              },
              *payload
            );
          });
        });
      }
    }

    void operator()(Args... args) const { emit(std::move(args)...); }
  };
} // namespace expo::modules::v2::cpp
