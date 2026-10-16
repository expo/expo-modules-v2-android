#pragma once

#include <memory>
#include <utility>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/Scheduler.h>

namespace expo::modules::v2::cpp::detail {
  class RuntimeData;

  /**
   * The part of an attached runtime that any thread may hold: how to reach its JS thread, and
   * whether it is still there.
   *
   * `RuntimeData` belongs to the JS thread and dies with the runtime. Whatever other threads keep -
   * an event's observers, a promise handle, a coroutine waiting to resume - keeps this instead, and
   * a job posted through it finds the runtime gone and does nothing, the way Kotlin's
   * `runtimePointer == 0` check does.
   *
   * The scheduler is held weakly. A job sits in the scheduler's queue and holds this link, so a
   * strong reference back would be a cycle that leaks the scheduler, and every job in it, whenever
   * it is destroyed with jobs still queued. `RuntimeData` owns the scheduler for as long as the
   * runtime is attached; after that there is nothing to post to, and a post is dropped.
   */
  class EXPO_API RuntimeLink : public std::enable_shared_from_this<RuntimeLink> {
  public:
    explicit RuntimeLink(const std::shared_ptr<Scheduler>& scheduler) : scheduler_(scheduler) {
    }

    [[nodiscard]] bool isOnJSThread() const {
      const std::shared_ptr<Scheduler> scheduler = scheduler_.lock();
      return scheduler != nullptr && scheduler->isOnJSThread();
    }

    /**
     * Runs `job(RuntimeData&)` on the JS thread: at once when the caller already is there, posted
     * otherwise. Nothing runs once the runtime is gone.
     */
    template<typename Job>
    void runOnJS(Job&& job) {
      if (isOnJSThread()) {
        if (RuntimeData* const data = data_) {
          job(*data);
        }
        return;
      }
      post(std::forward<Job>(job));
    }

    /**
     * [runOnJS], but always through the scheduler's queue.
     *
     * The caller keeps the scheduler alive for the whole call. The job may run on the JS thread
     * before `post` returns, and what it does - resuming a coroutine, say - can drop the last
     * reference to this link, and with it to the scheduler, while the posting thread is still inside
     * the scheduler's `post`, waking whoever waits for a job.
     */
    template<typename Job>
    void post(Job&& job) {
      const std::shared_ptr<Scheduler> scheduler = scheduler_.lock();
      if (scheduler == nullptr) {
        return;
      }
      scheduler->post([link = shared_from_this(), job = std::forward<Job>(job)]() mutable {
        if (RuntimeData* const data = link->data_) {
          job(*data);
        }
      });
    }

    /** The runtime's data, or null once it is gone. Only meaningful on the JS thread. */
    [[nodiscard]] RuntimeData* data() const noexcept { return data_; }

  private:
    friend class RuntimeData;

    std::weak_ptr<Scheduler> scheduler_;
    RuntimeData* data_ = nullptr;
  };
} // namespace expo::modules::v2::cpp::detail
