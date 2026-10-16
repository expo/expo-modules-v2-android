#include <expo-modules-cpp/Scheduler.h>

#include <algorithm>
#include <utility>
#include <vector>

namespace expo::modules::v2::cpp {
  ManualScheduler::ManualScheduler() : owner_(std::this_thread::get_id()) {
  }

  bool ManualScheduler::isOnJSThread() const {
    return std::this_thread::get_id() == owner_;
  }

  void ManualScheduler::post(std::function<void()> job) {
    {
      const std::lock_guard lock(mutex_);
      if (closed_) {
        return;
      }
      jobs_.push_back(std::move(job));
    }
    arrival_.notify_all();
  }

  size_t ManualScheduler::drain() {
    // Only the jobs there are now. A thread that posts faster than they run would otherwise keep this
    // going for ever, and the caller - whose own turn it is to run - would never get its thread back.
    size_t queued;
    {
      const std::lock_guard lock(mutex_);
      queued = jobs_.size();
    }

    size_t ran = 0;
    while (ran < queued) {
      std::function<void()> job;
      {
        const std::lock_guard lock(mutex_);
        if (jobs_.empty()) {
          return ran;
        }
        job = std::move(jobs_.front());
        jobs_.pop_front();
      }
      ran++;
      try {
        job();
      } catch (...) {
        // A job settles a promise or delivers an event, and both turn their own failures into
        // something JavaScript sees. Anything reaching here would only strand the jobs behind it.
      }
    }
    return ran;
  }

  bool ManualScheduler::waitForJob(const std::chrono::nanoseconds timeout) {
    std::unique_lock lock(mutex_);
    return arrival_.wait_for(lock, timeout, [this] { return closed_ || !jobs_.empty(); }) &&
           !jobs_.empty();
  }

  void ManualScheduler::close() {
    std::deque<std::function<void()>> discarded;
    {
      const std::lock_guard lock(mutex_);
      closed_ = true;
      discarded.swap(jobs_);
    }
    arrival_.notify_all();
    // Destroyed here, outside the lock: what a job holds - a coroutine frame, say - may post to this
    // scheduler as it dies.
    discarded.clear();
  }

  namespace {
    class ThreadPool final : public Executor {
    public:
      void execute(std::function<void()> job) override {
        {
          const std::lock_guard lock(mutex_);
          queue_.push_back(std::move(job));
          // A worker is started when no idle one can take the job, up to the pool's size.
          if (idle_ == 0 && workers_ < kMaxWorkers) {
            workers_++;
            std::thread([this] { run(); }).detach();
          }
        }
        wake_.notify_one();
      }

    private:
      static constexpr size_t kMaxWorkers = 4;

      void run() {
        std::unique_lock lock(mutex_);
        while (true) {
          idle_++;
          wake_.wait(lock, [this] { return !queue_.empty(); });
          idle_--;

          std::function<void()> job = std::move(queue_.front());
          queue_.pop_front();

          lock.unlock();
          try {
            job();
          } catch (...) {
            // A job reports its own failures; a worker must outlive a bad one.
          }
          // Released outside the lock: the job's captures may take any lock when they die.
          job = nullptr;
          lock.lock();
        }
      }

      std::mutex mutex_;
      std::condition_variable wake_;
      std::deque<std::function<void()>> queue_;
      size_t workers_ = 0;
      size_t idle_ = 0;
    };
  } // namespace

  Executor& backgroundExecutor() {
    // Never destroyed: its workers outlive main(), and a destructor racing a worker that still
    // reads the queue is a crash at exit for nothing.
    static ThreadPool& pool = *new ThreadPool();
    return pool;
  }
} // namespace expo::modules::v2::cpp
