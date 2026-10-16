#pragma once

#include <array>
#include <concepts>
#include <cstddef>
#include <exception>
#include <memory>
#include <optional>
#include <tuple>
#include <type_traits>
#include <utility>

#include <expo-modules-cpp/Converter.h>
#include <expo-modules-cpp/Promise.h>
#include <expo-modules-cpp/SharedObject.h>
#include <expo-modules-cpp/Task.h>
#include <expo-modules-cpp/internal/Invoke.h>
#include <expo-modules-cpp/internal/RuntimeData.h>
#include <expo-modules-cpp/internal/Specs.h>

// How an exported C++ callable becomes a `FunctionSpec`.
//
// Everything here is resolved when the compiler instantiates `Invoker` for a callable: what each
// parameter is (an argument JavaScript passes, the runtime, a promise, the object a member was
// called on), how many arguments a call may leave out, and whether the export is synchronous, takes
// a `Promise` or returns a `Task`. What is left at run time is the arity check, one conversion per
// argument, the call, and one conversion of the result - no allocation, no virtual call, no
// type-erased argument list.

namespace expo::modules::v2::cpp::detail {
  /** What a parameter of an exported callable is. */
  enum class ParamKind : uint8_t {
    /** `js::Runtime&`: the runtime the call came from. Takes no JavaScript argument. */
    Runtime,
    /** `cpp::Promise<T>`: the way to settle the promise the call returns. Takes no argument. */
    Promise,
    /** The object a member of a shared object class was called on. Takes no argument. */
    Receiver,
    /** One argument JavaScript passes. */
    Arg,
  };

  /**
   * Whether [P] names the class [Recv] (or a base of it) in one of the ways a member takes the
   * object it was called on: by reference, by pointer or by `std::shared_ptr`.
   */
  template<typename P, typename Recv>
  constexpr bool takesReceiver() {
    if constexpr (std::is_void_v<Recv>) {
      return false;
    } else {
      using U = Plain<P>;
      if constexpr (std::is_pointer_v<U>) {
        return std::is_base_of_v<std::remove_cv_t<std::remove_pointer_t<U>>, Recv>;
      } else if constexpr (kIsSpecialization<U, std::shared_ptr>) {
        return std::is_base_of_v<typename U::element_type, Recv>;
      } else {
        return std::is_class_v<U> && std::is_base_of_v<U, Recv>;
      }
    }
  }

  template<typename P, typename Recv, size_t Position>
  constexpr ParamKind kindOf() {
    using U = Plain<P>;
    if constexpr (std::is_same_v<U, js::Runtime> && std::is_lvalue_reference_v<P>) {
      return ParamKind::Runtime;
    } else if constexpr (kIsPromise<U>) {
      return ParamKind::Promise;
    } else if constexpr (Position == 0 && takesReceiver<P, Recv>()) {
      return ParamKind::Receiver;
    } else {
      return ParamKind::Arg;
    }
  }

  // --- the parameters ---------------------------------------------------------------------------

  /**
   * How an argument of type [P] is read from JavaScript and handed to the callable. [Held] is what
   * lives on the stack for the call.
   */
  template<typename P>
  struct ArgAdapter {
    using Converted = Plain<P>;
    using Held = Converted;

    static_assert(
      !std::is_lvalue_reference_v<P> || std::is_const_v<std::remove_reference_t<P>>,
      "An exported function cannot take a non-const reference: JavaScript passes a value, and "
      "there is nothing for the function to change. Take it by value or by const reference."
    );
    static_assert(
      !std::is_same_v<Converted, std::string_view> && !std::is_same_v<Converted, const char*>,
      "A parameter cannot be a std::string_view or a const char*: the string belongs to JavaScript. "
      "Take a std::string."
    );

    static constexpr bool kOptional = acceptsMissing<Converted>();

    static Held decode(js::Runtime& rt, const js::Value& value) { return Converter<Converted>::fromJS(rt, value); }

    static P pass(Held& held) { return static_cast<P>(std::move(held)); }
  };

  /** A `const js::Value&` borrows the argument itself: no copy, and no conversion. */
  template<typename P>
    requires(std::is_same_v<Plain<P>, js::Value> && std::is_reference_v<P>)
  struct ArgAdapter<P> {
    using Held = const js::Value&;

    static constexpr bool kOptional = true;

    static Held decode(js::Runtime&, const js::Value& value) { return value; }

    static P pass(Held held) { return held; }
  };

  /** A shared object by reference: the instance, kept alive for the call, and never null. */
  template<typename P>
    requires(SharedObjectType<Plain<P>> && std::is_reference_v<P>)
  struct ArgAdapter<P> {
    using Object = Plain<P>;
    using Held = std::shared_ptr<Object>;

    static constexpr bool kOptional = false;

    static Held decode(js::Runtime& rt, const js::Value& value) {
      if (value.isUndefined() || value.isNull()) [[unlikely]] {
        throwExpected(rt, "a shared object", value);
      }
      return std::static_pointer_cast<Object>(sharedObjectFromJS(rt, value, classSpecOf<Object>()));
    }

    static P pass(Held& held) { return *held; }
  };

  /** A shared object by pointer: null when JavaScript passed `null` or left the argument out. */
  template<typename P>
    requires(std::is_pointer_v<P> && SharedObjectType<std::remove_cv_t<std::remove_pointer_t<P>>>)
  struct ArgAdapter<P> {
    using Object = std::remove_cv_t<std::remove_pointer_t<P>>;
    using Held = std::shared_ptr<Object>;

    static constexpr bool kOptional = true;

    static Held decode(js::Runtime& rt, const js::Value& value) {
      return Converter<std::shared_ptr<Object>>::fromJS(rt, value);
    }

    static P pass(Held& held) { return held.get(); }
  };

  /** The object a member was called on, in the form the callable asks for it. */
  template<typename P>
  struct ReceiverAdapter {
    using U = Plain<P>;

    static constexpr bool kSharedPtr = kIsSpecialization<U, std::shared_ptr>;

    /** A reference or a pointer to the object itself, for the common forms. */
    static constexpr bool kByAddress = !kSharedPtr && (std::is_reference_v<P> || std::is_pointer_v<U>);

    /** A bare pointer for the common forms, and ownership for a `std::shared_ptr` parameter. */
    using Held = std::conditional_t<kByAddress, NativeObject*, std::shared_ptr<NativeObject>>;

    /**
     * What the callable receives. A `std::shared_ptr` is handed over as a value even when the
     * parameter is a `const std::shared_ptr<C>&`: the reference then binds to it for the call, where
     * a reference to anything kept here would not outlive [pass].
     */
    using Passed = std::conditional_t<kSharedPtr, U, P>;

    static Held get(NativeObject& object) {
      if constexpr (kByAddress) {
        return &object;
      } else {
        return object.shared_from_this();
      }
    }

    static Passed pass(Held& held) {
      if constexpr (kSharedPtr) {
        return std::static_pointer_cast<typename U::element_type>(held);
      } else if constexpr (std::is_reference_v<P>) {
        return static_cast<P>(*static_cast<U*>(held));
      } else {
        return static_cast<U>(held);
      }
    }
  };

  template<typename T>
  struct PromiseValue;

  template<typename T>
  struct PromiseValue<Promise<T>> {
    using Type = T;
  };

  template<typename T>
  struct TaskValue_;

  template<typename T>
  struct TaskValue_<Task<T>> {
    using Type = T;
  };

  /** What one call of an exported callable carries besides JavaScript's own arguments. */
  struct InvokeContext {
    const CallFrame& frame;
    NativeObject* receiver = nullptr;
    std::shared_ptr<PromiseCore> promise{};
    /** The argument being read, for the error a bad one is reported with. */
    size_t argument = 0;
  };

  template<ParamKind Kind, typename P>
  struct Slot;

  template<typename P>
  struct Slot<ParamKind::Runtime, P> {
    using Held = js::Runtime&;

    static constexpr bool kOptional = true;

    static Held get(InvokeContext& context, size_t) { return context.frame.rt; }

    static P pass(Held held) { return held; }
  };

  template<typename P>
  struct Slot<ParamKind::Promise, P> {
    using Held = Plain<P>;

    static constexpr bool kOptional = true;

    static Held get(InvokeContext& context, size_t) {
      return PromiseAccess::make<typename PromiseValue<Plain<P>>::Type>(context.promise);
    }

    static P pass(Held& held) { return static_cast<P>(std::move(held)); }
  };

  template<typename P>
  struct Slot<ParamKind::Receiver, P> {
    using Adapter = ReceiverAdapter<P>;
    using Held = typename Adapter::Held;

    static constexpr bool kOptional = true;

    static Held get(InvokeContext& context, size_t) { return Adapter::get(*context.receiver); }

    static typename Adapter::Passed pass(Held& held) { return Adapter::pass(held); }
  };

  template<typename P>
  struct Slot<ParamKind::Arg, P> {
    using Adapter = ArgAdapter<P>;
    using Held = typename Adapter::Held;

    static constexpr bool kOptional = Adapter::kOptional;

    static Held get(InvokeContext& context, const size_t index) {
      const CallFrame& frame = context.frame;
      context.argument = index;
      // A call may stop short of the trailing optional arguments, which are `undefined`.
      return Adapter::decode(frame.rt, index < frame.count ? frame.args[index] : undefinedValue());
    }

    static P pass(Held& held) { return Adapter::pass(held); }
  };

  /**
   * The promise of a call that returns one: its entry in the runtime's table, dropped again if the
   * call fails before it gets as far as returning the promise. Synchronous calls have none.
   */
  template<bool Async>
  class PromiseScope {
  public:
    explicit PromiseScope(InvokeContext&) {
    }
  };

  template<>
  class PromiseScope<true> {
  public:
    explicit PromiseScope(InvokeContext& context)
      : data_(RuntimeData::require(context.frame.rt)), id_(data_.promises().begin()) {
      context.promise = std::make_shared<PromiseCore>(data_.link(), id_);
    }

    PromiseScope(const PromiseScope&) = delete;
    PromiseScope& operator=(const PromiseScope&) = delete;

    ~PromiseScope() {
      if (armed_) {
        data_.promises().discard(id_);
      }
    }

    /** The promise JavaScript receives, pending or already settled. */
    js::Value finish(js::Runtime& rt) {
      armed_ = false;
      return data_.promises().finish(rt, id_);
    }

  private:
    RuntimeData& data_;
    uint64_t id_;
    bool armed_ = true;
  };

  /** Whether a result of type [R] is a shared object the call may have constructed. */
  template<typename R>
  inline constexpr bool kReturnsSharedObject = false;

  template<typename T>
  inline constexpr bool kReturnsSharedObject<std::shared_ptr<T>> = SharedObjectType<T>;

  /**
   * Binds the shared objects a call constructs to the context of the runtime it was made from, for as
   * long as the call runs. Only calls that return a shared object have one; for any other it is empty
   * and costs nothing.
   */
  template<bool Binds>
  class ConstructionGuard {
  public:
    explicit ConstructionGuard(const CallFrame&) {
    }
  };

  template<>
  class ConstructionGuard<true> {
  public:
    explicit ConstructionGuard(const CallFrame& frame) : scope_(RuntimeData::require(frame.rt).context().get()) {
    }

  private:
    ConstructionScope scope_;
  };

  // --- the export ---------------------------------------------------------------------------------

  template<typename F, typename Recv, typename Params>
  struct InvokerFor;

  /**
   * [F] is the callable, [Recv] the class of a shared object whose member it is - or void - and
   * [P...] its parameters.
   */
  template<typename F, typename Recv, typename... P>
  struct InvokerFor<F, Recv, std::tuple<P...>> {
    using Return = typename CallableTraits<F>::Return;

    static constexpr size_t kParams = sizeof...(P);

    template<size_t... I>
    static consteval std::array<ParamKind, kParams> classify(std::index_sequence<I...>) {
      return {kindOf<P, Recv, I>()...};
    }

    static constexpr std::array<ParamKind, kParams> kKinds = classify(std::index_sequence_for<P...>{});

    template<size_t I>
    using ParamAt = std::tuple_element_t<I, std::tuple<P...>>;

    template<size_t I>
    using SlotAt = Slot<kKinds[I], ParamAt<I>>;

    static consteval size_t countOf(const ParamKind kind) {
      size_t count = 0;
      for (const ParamKind each: kKinds) {
        count += each == kind ? 1 : 0;
      }
      return count;
    }

    /** The parameters JavaScript passes. */
    static constexpr size_t kJsArgs = countOf(ParamKind::Arg);

    /** Where each parameter is among the ones JavaScript passes: its position in `args`. */
    static consteval std::array<size_t, kParams> jsIndices() {
      std::array<size_t, kParams> result{};
      size_t next = 0;
      for (size_t i = 0; i < kParams; i++) {
        result[i] = next;
        next += kKinds[i] == ParamKind::Arg ? 1 : 0;
      }
      return result;
    }

    static constexpr std::array<size_t, kParams> kJsIndex = jsIndices();

    /** The arguments a call has to pass: everything up to the last one that cannot be left out. */
    template<size_t... I>
    static consteval size_t requiredArgs(std::index_sequence<I...>) {
      size_t result = 0;
      ((result = kKinds[I] == ParamKind::Arg && !SlotAt<I>::kOptional ? kJsIndex[I] + 1 : result), ...);
      return result;
    }

    static constexpr size_t kRequired = requiredArgs(std::index_sequence_for<P...>{});

    static constexpr bool kHasReceiver = countOf(ParamKind::Receiver) == 1;
    static constexpr size_t kPromiseParams = countOf(ParamKind::Promise);
    static constexpr bool kIsTask = kIsSpecialization<Return, Task>;
    static constexpr bool kAsync = kPromiseParams > 0 || kIsTask;

    static_assert(kPromiseParams <= 1, "An exported function takes at most one cpp::Promise");
    static_assert(
      kPromiseParams == 0 || std::is_void_v<Return>,
      "A function that takes a cpp::Promise settles it itself, so it returns void"
    );
    static_assert(
      !(kPromiseParams > 0 && kIsTask),
      "A function either takes a cpp::Promise or returns a cpp::Task, not both"
    );

    static consteval bool coroutineTakesNoReferences() {
      bool ok = true;
      ((ok = ok && (!std::is_reference_v<P> || std::is_same_v<Plain<P>, js::Runtime> || takesReceiver<P, Recv>())), ...);
      return ok;
    }

    static_assert(
      !kIsTask || coroutineTakesNoReferences(),
      "A coroutine export takes its parameters by value: the arguments are gone once the call "
      "returns and the coroutine suspends, so a reference would dangle"
    );

    /**
     * Rejects the call's promise with the exception being handled. One that comes after the promise
     * was settled has nobody to tell, so it is logged rather than lost.
     */
    static void rejectWithCurrentException(InvokeContext& context) {
      if (context.promise->trySettle()) {
        context.promise->rejectWith(std::current_exception());
      } else {
        logCurrentException("An exported function threw after it settled its promise");
      }
    }

    template<size_t... I>
    static void callTask(InvokeContext& context, std::tuple<typename SlotAt<I>::Held...>& held, F& callable) {
      using Result = typename TaskValue_<Return>::Type;
      using Stored = typename Return::State::Stored;

      // The object the callable belongs to stays alive until the coroutine ends: the coroutine runs
      // on, and what it holds is a pointer to its closure, which lives in that object. For a member
      // that is the receiver; for a function of a module, the module - a runtime torn down while a
      // coroutine is suspended must not take it away.
      std::shared_ptr<NativeObject> keepAlive;
      if constexpr (kHasReceiver) {
        keepAlive = context.receiver->shared_from_this();
      } else if (context.frame.owner != nullptr) {
        keepAlive = context.frame.owner->shared_from_this();
      }

      try {
        Return task = callable(SlotAt<I>::pass(std::get<I>(held))...);
        task.then([core = context.promise, keepAlive = std::move(keepAlive)](
          std::optional<Stored>&& value,
          std::exception_ptr error
        ) {
          Promise<Result> promise = PromiseAccess::make<Result>(core);
          if (error) {
            promise.reject(std::move(error));
          } else if constexpr (std::is_void_v<Result>) {
            promise.resolve();
          } else {
            promise.resolve(std::move(*value));
          }
        });
      } catch (...) {
        rejectWithCurrentException(context);
      }
    }

    template<size_t... I>
    static js::Value run(const FunctionSpec& spec, const CallFrame& frame, std::index_sequence<I...>) {
      F& callable = *static_cast<F*>(spec.callable.get());
      InvokeContext context{.frame = frame};

      if constexpr (kHasReceiver) {
        context.receiver = &receiverOf(frame, spec);
      }

      PromiseScope<kAsync> promise(context);
      ConstructionGuard<kReturnsSharedObject<Plain<Return>>> construction(frame);

      Phase phase = Phase::Decoding;
      try {
        // A braced list evaluates left to right, so the arguments are read in order and the first
        // bad one is the one reported.
        std::tuple<typename SlotAt<I>::Held...> held{SlotAt<I>::get(context, kJsIndex[I])...};
        phase = Phase::Running;

        if constexpr (kIsTask) {
          callTask<I...>(context, held, callable);
          return promise.finish(frame.rt);
        } else if constexpr (kPromiseParams > 0) {
          try {
            callable(SlotAt<I>::pass(std::get<I>(held))...);
          } catch (...) {
            // A function that fails after it was handed a promise rejects it: the caller already
            // has the promise to wait on.
            rejectWithCurrentException(context);
          }
          return promise.finish(frame.rt);
        } else if constexpr (std::is_void_v<Return>) {
          callable(SlotAt<I>::pass(std::get<I>(held))...);
          return js::Value::undefined();
        } else {
          decltype(auto) result = callable(SlotAt<I>::pass(std::get<I>(held))...);
          phase = Phase::Encoding;
          return Converter<Plain<Return>>::toJS(frame.rt, std::forward<decltype(result)>(result));
        }
      } catch (const ConversionError& error) {
        rethrowConversion(spec, phase, context.argument, error);
      }
    }

    static js::Value invoke(const FunctionSpec& spec, const CallFrame& frame) {
      const size_t count = frame.count;
      if (count != kJsArgs) [[unlikely]] {
        // Fewer is fine when everything missing is optional; more never is.
        if (count > kJsArgs || count < kRequired) {
          throwArity(spec, count, kJsArgs);
        }
      }
      return run(spec, frame, std::index_sequence_for<P...>{});
    }
  };

  template<typename F, typename Recv>
  using Invoker = InvokerFor<F, Recv, typename CallableTraits<F>::Params>;

  /**
   * An exported function for [f]. [Recv] is the shared object class the callable is a member of, or
   * void for a function of a module.
   */
  template<typename Recv = void, typename F>
  FunctionSpec makeFunction(std::string name, F&& f) {
    using Callable = std::decay_t<F>;
    using Exported = Invoker<Callable, Recv>;

    FunctionSpec spec;
    spec.name = std::move(name);
    spec.callable = std::make_shared<Callable>(std::forward<F>(f));
    spec.invoke = &Exported::invoke;
    spec.arity = static_cast<uint32_t>(Exported::kJsArgs);
    return spec;
  }
} // namespace expo::modules::v2::cpp::detail
