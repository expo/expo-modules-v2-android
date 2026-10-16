#pragma once

#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>

#include <expo-modules-cpp/Defines.h>

namespace expo::modules::v2::cpp {
  /**
   * How native code gets back to a runtime's JS thread: Kotlin's `JSScheduler`. The host that owns
   * the `js::Runtime` implements it, because only the host knows its thread (React Native's JS
   * queue, a looper, an event loop of its own).
   *
   * A runtime is thread-affine, so everything that touches JavaScript from another thread -
   * settling a promise, delivering an event - goes through [post].
   */
  class EXPO_API Scheduler {
  public:
    virtual ~Scheduler() = default;

    /** Whether the calling thread is the JS thread, so [post] would be a detour. */
    [[nodiscard]] virtual bool isOnJSThread() const = 0;

    /**
     * Enqueues [job] for the JS thread. Safe from any thread. Never throws, and silently drops the
     * job once the runtime has been closed.
     *
     * The job runs outside any host function, so a host that has to run microtasks after its own
     * jobs (React Native does) does it here, after the job and never inside it.
     */
    virtual void post(std::function<void()> job) = 0;

    /** Stops accepting jobs and discards the queue. Idempotent. */
    virtual void close() {}
  };

  /**
   * A scheduler for hosts without an event loop of their own - tests, tools, a desktop shell. The
   * thread that constructs it is the JS thread; it runs the jobs other threads posted when it calls
   * [drain].
   */
  class EXPO_API ManualScheduler final : public Scheduler {
  public:
    ManualScheduler();

    [[nodiscard]] bool isOnJSThread() const override;

    void post(std::function<void()> job) override;

    /**
     * Runs the jobs queued right now - not the ones posted while it runs, which the next call takes -
     * and returns how many ran. Must run on the JS thread, and not from inside a host function. A job
     * that throws is dropped, so one failing job cannot strand the rest.
     */
    size_t drain();

    /** Blocks until a job is queued or [timeout] elapses; returns whether one is. */
    bool waitForJob(std::chrono::nanoseconds timeout);

    void close() override;

  private:
    const std::thread::id owner_;
    std::mutex mutex_;
    std::condition_variable arrival_;
    std::deque<std::function<void()>> jobs_;
    bool closed_ = false;
  };

  /**
   * Somewhere to run work off the JS thread. [execute] never runs the job on the calling thread
   * before returning, so a caller can hold a lock across it.
   */
  class EXPO_API Executor {
  public:
    virtual ~Executor() = default;

    virtual void execute(std::function<void()> job) = 0;
  };

  /**
   * The shared pool a `co_await cpp::background()` and a `cpp::background(fn)` function run on.
   * Created on first use, with a few threads that start as the load needs them, and kept until the
   * process ends.
   */
  EXPO_API Executor& backgroundExecutor();
} // namespace expo::modules::v2::cpp
