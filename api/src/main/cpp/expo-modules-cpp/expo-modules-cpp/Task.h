#pragma once

#include <coroutine>
#include <exception>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <type_traits>
#include <utility>
#include <variant>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/Scheduler.h>
#include <expo-modules-cpp/Traits.h>
#include <expo-modules-cpp/internal/RuntimeData.h>
#include <expo-modules-cpp/internal/RuntimeLink.h>

namespace expo::modules::v2::cpp::detail {
  /** What a `Task<void>` holds in place of a value. */
  using TaskValue = std::monostate;

  /**
   * The outcome of a coroutine, shared between the coroutine and whoever waits for it. Thread-safe:
   * the coroutine completes on whichever thread it ended on, and the waiter registers from another.
   */
  template<typename T>
  class TaskState {
  public:
    using Stored = std::conditional_t<std::is_void_v<T>, TaskValue, T>;
    using Continuation = std::function<void(std::optional<Stored>&&, std::exception_ptr)>;

    template<typename U>
    void complete(U&& value) {
      finish(std::optional<Stored>(std::forward<U>(value)), nullptr);
    }

    void complete() {
      finish(std::optional<Stored>(std::in_place), nullptr);
    }

    void fail(std::exception_ptr error) { finish(std::nullopt, std::move(error)); }

    /**
     * Runs [continuation] with the outcome: at once if the coroutine is done, on the thread that
     * completes it otherwise. At most one continuation is kept.
     */
    void then(Continuation continuation) {
      std::optional<Stored> value;
      std::exception_ptr error;
      {
        const std::lock_guard lock(mutex_);
        if (!done_) {
          continuation_ = std::move(continuation);
          return;
        }
        value = std::move(value_);
        error = error_;
      }
      continuation(std::move(value), std::move(error));
    }

    [[nodiscard]] bool isDone() {
      const std::lock_guard lock(mutex_);
      return done_;
    }

  private:
    void finish(std::optional<Stored>&& value, std::exception_ptr error) {
      Continuation continuation;
      {
        const std::lock_guard lock(mutex_);
        done_ = true;
        if (continuation_) {
          continuation = std::move(continuation_);
          continuation_ = nullptr;
        } else {
          value_ = std::move(value);
          error_ = std::move(error);
          return;
        }
      }
      continuation(std::move(value), std::move(error));
    }

    std::mutex mutex_;
    bool done_ = false;
    std::optional<Stored> value_;
    std::exception_ptr error_;
    Continuation continuation_;
  };
} // namespace expo::modules::v2::cpp::detail

namespace expo::modules::v2::cpp {
  template<typename T = void>
  class Task;

  namespace detail {
    /**
     * A suspended coroutine queued to resume. If the queue is destroyed with the job unrun - the
     * runtime went away - the coroutine is destroyed instead, rather than left suspended forever
     * with everything it holds.
     */
    class ResumeOnce {
    public:
      explicit ResumeOnce(const std::coroutine_handle<> handle) : handle_(handle) {
      }

      ResumeOnce(const ResumeOnce&) = delete;
      ResumeOnce& operator=(const ResumeOnce&) = delete;

      ~ResumeOnce() {
        if (handle_) {
          handle_.destroy();
        }
      }

      void run() {
        const std::coroutine_handle<> handle = std::exchange(handle_, nullptr);
        if (handle) {
          handle.resume();
        }
      }

    private:
      std::coroutine_handle<> handle_;
    };

    /** What a coroutine's promise object has in common, whether it returns a value or not. */
    template<typename T>
    struct TaskPromiseBase {
      std::shared_ptr<TaskState<T>> state = std::make_shared<TaskState<T>>();

      // Eager, and the frame frees itself when the coroutine ends: the task is only a way to learn
      // how it went, so nothing has to keep it alive.
      std::suspend_never initial_suspend() noexcept { return {}; }

      std::suspend_never final_suspend() noexcept { return {}; }

      void unhandled_exception() { state->fail(std::current_exception()); }
    };

    template<typename T>
    struct TaskPromise : TaskPromiseBase<T> {
      Task<T> get_return_object();

      template<typename U>
      void return_value(U&& value) {
        this->state->complete(std::forward<U>(value));
      }
    };

    template<>
    struct TaskPromise<void> : TaskPromiseBase<void> {
      Task<void> get_return_object();

      void return_void() { state->complete(); }
    };
  } // namespace detail

  /**
   * A C++20 coroutine's result: Kotlin's `suspend`. An exported function that returns a `Task`
   * returns a promise to JavaScript, which settles with what the coroutine returns or throws.
   *
   * ```cpp
   * def.function("read", [](std::string path) -> cpp::Task<std::string> {
   *   co_await cpp::background();     // off the JS thread, for the blocking part
   *   std::string text = readFile(path);
   *   co_return text;                 // JavaScript gets it, on the JS thread
   * });
   * ```
   *
   * The coroutine starts at once, on the calling JS thread, and runs until its first suspension -
   * as a Kotlin `suspend` export does - then continues on whichever thread resumes it. It must not
   * touch a `js::Value` after it suspended; `co_await cpp::onJSThread(rt)` goes back to the JS thread
   * to do that. Parameters of a coroutine are copied into it, so take them by value: a reference
   * would dangle once the call returned.
   */
  template<typename T>
  class [[nodiscard]] Task {
  public:
    using State = detail::TaskState<T>;

    using promise_type = detail::TaskPromise<T>;

    /**
     * Calls [continuation] with the outcome - a value, or the exception - on the thread that
     * finishes the coroutine, or at once if it already has. Only one continuation is kept.
     */
    void then(typename State::Continuation continuation) const { state_->then(std::move(continuation)); }

    [[nodiscard]] bool isDone() const { return state_->isDone(); }

    /** Lets another coroutine wait for this one. */
    auto operator co_await() const {
      struct Awaiter {
        std::shared_ptr<State> state;
        std::optional<typename State::Stored> value;
        std::exception_ptr error;

        bool await_ready() const { return state->isDone(); }

        void await_suspend(std::coroutine_handle<> waiter) {
          state->then([this, waiter](std::optional<typename State::Stored>&& result, std::exception_ptr failure) {
            value = std::move(result);
            error = std::move(failure);
            waiter.resume();
          });
        }

        T await_resume() {
          if (error) {
            std::rethrow_exception(error);
          }
          if constexpr (!std::is_void_v<T>) {
            return std::move(*value);
          }
        }
      };
      return Awaiter{.state = state_, .value = std::nullopt, .error = nullptr};
    }

  private:
    friend struct detail::TaskPromise<T>;

    explicit Task(std::shared_ptr<State> state) : state_(std::move(state)) {
    }

    std::shared_ptr<State> state_;
  };

  namespace detail {
    template<typename T>
    Task<T> TaskPromise<T>::get_return_object() {
      return Task<T>(this->state);
    }

    inline Task<void> TaskPromise<void>::get_return_object() {
      return Task<void>(state);
    }
  } // namespace detail

  namespace detail {
    template<typename T>
    inline constexpr bool kIsTask = kIsSpecialization<T, Task>;
  } // namespace detail

  /** `co_await cpp::background()` continues the coroutine on a thread of the shared pool. */
  inline auto background() {
    struct Awaiter {
      bool await_ready() const noexcept { return false; }

      void await_suspend(std::coroutine_handle<> handle) const {
        backgroundExecutor().execute([handle] { handle.resume(); });
      }

      void await_resume() const noexcept {}
    };
    return Awaiter{};
  }

  /** `co_await cpp::onExecutor(e)` continues the coroutine on [e]. */
  inline auto onExecutor(Executor& executor) {
    struct Awaiter {
      Executor& executor;

      bool await_ready() const noexcept { return false; }

      void await_suspend(std::coroutine_handle<> handle) const {
        executor.execute([handle] { handle.resume(); });
      }

      void await_resume() const noexcept {}
    };
    return Awaiter{.executor = executor};
  }

  /**
   * `co_await cpp::onJSThread(rt)` continues the coroutine on the JS thread of [rt] - at once when it
   * already is on it - which is where a `js::Value` may be touched again. A coroutine that suspended
   * elsewhere keeps the `js::Runtime&` it was handed and passes it here; the reference stays valid
   * for as long as the runtime is attached, and only its identity is read from another thread.
   */
  inline auto onJSThread(js::Runtime& rt) {
    struct Awaiter {
      std::shared_ptr<detail::RuntimeLink> link;

      bool await_ready() const { return link->isOnJSThread(); }

      void await_suspend(std::coroutine_handle<> handle) {
        // Moved out, not copied: the coroutine may resume on another thread as soon as the job is
        // queued and destroy this awaiter before `post` returns, so what `post` uses is a local; and
        // a suspended frame that still held the link would be a cycle with a scheduler that is
        // destroyed with the job unrun.
        const std::shared_ptr<detail::RuntimeLink> keep = std::move(link);
        keep->post([resume = std::make_shared<detail::ResumeOnce>(handle)](detail::RuntimeData&) { resume->run(); });
      }

      void await_resume() const noexcept {}
    };
    return Awaiter{.link = detail::RuntimeData::requireLink(rt)};
  }

  namespace detail {
    template<typename F, typename Params>
    struct BackgroundCall;

    /**
     * [F] as a coroutine: it takes the arguments by value, hops to the pool, calls [F] there and
     * returns what it returns. The export machinery already knows how to run that.
     */
    template<typename F, typename... P>
    struct BackgroundCall<F, std::tuple<P...>> {
      using Return = typename CallableTraits<F>::Return;
      using Result = std::conditional_t<std::is_void_v<Return>, void, std::decay_t<Return>>;

      static_assert(
        (... && !std::is_same_v<Plain<P>, js::Runtime>),
        "A function that runs in the background cannot take the js::Runtime: it is not on the JS thread"
      );

      F function;

      Task<Result> operator()(std::decay_t<P>... arguments) const {
        // A copy, because the call returns long before the pool runs it, and the export that owns
        // `this` is free to go away in between.
        F callable = function;
        co_await background();
        if constexpr (std::is_void_v<Result>) {
          callable(std::move(arguments)...);
        } else {
          co_return callable(std::move(arguments)...);
        }
      }
    };
  } // namespace detail

  /**
   * Makes [function] run off the JS thread and return a promise: the arguments are read on the JS
   * thread, [function] runs on the shared pool with them, and the result is converted and delivered
   * on the JS thread - Kotlin's `withContext(Dispatchers.IO)`, without writing a coroutine.
   *
   * ```cpp
   * def.function("sha256", cpp::background([](std::string text) { return sha256(text); }));
   * ```
   *
   * [function] must not touch JavaScript, so it takes plain C++ values, not a `js::Value` or the
   * `js::Runtime`.
   */
  template<typename F>
  auto background(F&& function) {
    using Callable = std::decay_t<F>;
    return detail::BackgroundCall<Callable, typename detail::CallableTraits<Callable>::Params>{
      .function = std::forward<F>(function),
    };
  }
} // namespace expo::modules::v2::cpp
