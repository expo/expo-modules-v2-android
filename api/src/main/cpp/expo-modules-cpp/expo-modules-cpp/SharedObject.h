#pragma once

#include <atomic>
#include <concepts>
#include <memory>
#include <string>
#include <utility>

#include <expo-modules-cpp/Converter.h>
#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>
#include <expo-modules-cpp/Traits.h>
#include <expo-modules-cpp/internal/Specs.h>

namespace expo::modules::v2::cpp {
  template<typename T>
  class ClassDefinition;

  /**
   * A native object JavaScript holds a reference to, rather than a value copied across: Kotlin's
   * `SharedObject`. Derive from it, describe the class with a static `define`, and hand instances
   * to JavaScript as `std::shared_ptr`:
   *
   * ```cpp
   * class Counter : public cpp::SharedObject {
   * public:
   *   static void define(cpp::ClassDefinition<Counter>& cls) {
   *     cls.constructor<int>();
   *     cls.function("increment", &Counter::increment);
   *     cls.property("value", &Counter::value_);
   *   }
   *
   *   explicit Counter(int start) : value_(start) {}
   *   int increment() { return ++value_; }
   *
   * private:
   *   int value_;
   * };
   * ```
   *
   * JavaScript and native code share the instance: it lives while either can still reach it, and
   * the same instance is the same JavaScript object, in every call, for as long as JavaScript holds
   * it. The destructor may run on any thread - the garbage collector's, when the last reference was
   * JavaScript's - so it must not touch JavaScript.
   */
  class EXPO_API SharedObject : public NativeObject {
  public:
    ~SharedObject() override;

    /**
     * Called once, when the object is released - by JavaScript's `release()` or by [release] - on
     * the thread that released it. Free what must not wait for the garbage collector here; the
     * object itself lives until its last reference goes.
     */
    virtual void didRelease() {}

    /**
     * The bytes this object keeps alive that the garbage collector cannot see, such as a decoded
     * image. Counted toward its memory pressure from the moment the object reaches JavaScript.
     */
    [[nodiscard]] virtual size_t externalMemoryPressure() const { return 0; }

    [[nodiscard]] bool isReleased() const noexcept { return released_.load(std::memory_order_acquire); }

    /** Releases the object, as JavaScript's `release()` does. Safe from any thread; once only. */
    void release();

  protected:
    SharedObject() = default;

  private:
    friend class detail::RuntimeData;

    /** The class JavaScript sees this object as: set when it first reaches JavaScript. */
    std::atomic<const detail::ClassSpec*> spec_{nullptr};
    std::atomic<bool> released_{false};
  };

  namespace detail {
    /** What a `SharedRef` exposes beyond its value: the name of the type it wraps. */
    class EXPO_API SharedRefBase : public SharedObject {
    public:
      [[nodiscard]] virtual std::string nativeRefType() const = 0;

    protected:
      SharedRefBase() = default;
    };
  } // namespace detail

  /**
   * A shared object that is an opaque handle to a native value: Kotlin's `SharedRef`. JavaScript
   * cannot see inside it, only pass it to the functions that take it back. It exports
   * `nativeRefType`, the name of the wrapped type.
   */
  template<typename T>
  class SharedRef : public detail::SharedRefBase {
  public:
    /** Constructs the wrapped value in place, so it need not be copyable or movable. */
    template<typename... Args>
      requires(std::is_constructible_v<T, Args&&...>)
    explicit SharedRef(Args&&... args) : ref_(std::forward<Args>(args)...) {
    }

    T& ref() noexcept { return ref_; }

    const T& ref() const noexcept { return ref_; }

    [[nodiscard]] std::string nativeRefType() const override { return std::string(detail::typeName<T>()); }

  private:
    T ref_;
  };

  namespace detail {
    /**
     * The class table for [T], built once from `T::define`. Defined with the definition classes it
     * runs; declared here for the converters that only need to name it.
     */
    template<typename T>
    EXPO_API const ClassSpec& classSpecOf();

    /** The object [value] stands for, checked to be a live [declared] or a subclass; throws `ConversionError`. */
    EXPO_API std::shared_ptr<SharedObject> sharedObjectFromJS(
      js::Runtime& rt,
      const js::Value& value,
      const ClassSpec& declared
    );

    EXPO_API js::Value sharedObjectToJS(
      js::Runtime& rt,
      const std::shared_ptr<SharedObject>& object,
      const ClassSpec& declared
    );

    template<typename T>
    concept SharedObjectType = std::derived_from<T, SharedObject>;
  } // namespace detail

  /**
   * A shared object as a result or a parameter. As a parameter, `null` and `undefined` are a null
   * pointer; take a `T&` instead to refuse them.
   */
  template<detail::SharedObjectType T>
  struct Converter<std::shared_ptr<T>> {
    static constexpr bool kOptional = true;

    static std::shared_ptr<T> fromJS(js::Runtime& rt, const js::Value& value) {
      if (value.isUndefined() || value.isNull()) {
        return nullptr;
      }
      return std::static_pointer_cast<T>(detail::sharedObjectFromJS(rt, value, detail::classSpecOf<T>()));
    }

    static js::Value toJS(js::Runtime& rt, const std::shared_ptr<T>& value) {
      if (value == nullptr) {
        return js::Value::null();
      }
      return detail::sharedObjectToJS(rt, value, detail::classSpecOf<T>());
    }
  };
} // namespace expo::modules::v2::cpp
