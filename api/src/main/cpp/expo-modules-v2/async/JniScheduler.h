#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <thread>

#include <jni.h>

#include <kolibri/Ref.h>

#include <expo-modules-cpp/Scheduler.h>
#include <expo-modules-v2/jni/JAsyncContext.h>

namespace expo::modules::v2::async {
  /**
   * How native modules written with the C++ API reach a runtime's JS thread: through the Kotlin
   * `AsyncContext`, whose `JSScheduler` is the one the host gave the runtime - React Native's JS
   * queue, or the desktop host's loop - so a job runs where every Kotlin job does.
   *
   * A job is a `std::function`, which Kotlin cannot hold; the scheduler keeps it and hands Kotlin its
   * id, and Kotlin's `Runnable` gives the id back on the JS thread. Jobs are kept in one table for
   * the process, so an id that outlives its scheduler - Kotlin drops a job posted after it closed,
   * silently - finds nothing instead of a freed object.
   */
  class JniScheduler final : public cpp::Scheduler {
  public:
    /** Must be created on the runtime's JS thread. */
    JniScheduler(JNIEnv* env, jobject asyncContext);

    ~JniScheduler() override;

    [[nodiscard]] bool isOnJSThread() const override;

    void post(std::function<void()> job) override;

    /** Drops the jobs not yet run, and every one posted after. */
    void close() override;

    /** Runs the job [id] if it still exists. Called by Kotlin's `Runnable`, on the JS thread. */
    static void run(uint64_t id);

  private:
    kolibri::GlobalRef<JAsyncContext> context_;

    /** The JS thread when it is known to be the thread that created this, so asking costs no JNI call. */
    std::thread::id jsThread_;
    bool jsThreadKnown_;

    std::atomic<bool> closed_{false};
  };
} // namespace expo::modules::v2::async
