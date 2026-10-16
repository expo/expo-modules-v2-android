#include <expo-modules-v2/async/JniScheduler.h>

#include <mutex>
#include <unordered_map>
#include <utility>
#include <vector>

#include <kolibri/defines.h>
#include <kolibri/env.h>

#include <expo-modules-v2/utils/Log.h>

namespace expo::modules::v2::async {
  namespace {
    struct Job {
      std::function<void()> run;
      const JniScheduler* owner;
    };

    std::mutex& jobsMutex() {
      NO_DESTROY static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<uint64_t, Job>& jobs() {
      NO_DESTROY static std::unordered_map<uint64_t, Job> table;
      return table;
    }

    uint64_t nextJobId = 1;
  } // namespace

  JniScheduler::JniScheduler(JNIEnv* env, jobject asyncContext)
    : context_(kolibri::GlobalRef<JAsyncContext>::make(env, asyncContext)),
      jsThread_(std::this_thread::get_id()),
      // Only trusted if Kotlin agrees this thread is the JS thread: a host that builds the runtime
      // elsewhere gets a JNI call per question, which is right if slower.
      jsThreadKnown_(context_->isOnJSThread(env)) {
  }

  JniScheduler::~JniScheduler() {
    close();
    // The last owner may be a thread the JVM does not know yet.
    if (context_) {
      kolibri::getEnv();
      context_.reset();
    }
  }

  bool JniScheduler::isOnJSThread() const {
    if (jsThreadKnown_) {
      return std::this_thread::get_id() == jsThread_;
    }
    return context_->isOnJSThread(kolibri::getEnv());
  }

  void JniScheduler::post(std::function<void()> job) {
    uint64_t id;
    {
      const std::lock_guard lock(jobsMutex());
      // Checked under the lock `close` sweeps under: a job registered here is found by that sweep,
      // and one that comes after finds the scheduler closed.
      if (closed_.load(std::memory_order_acquire)) {
        return;
      }
      id = nextJobId++;
      jobs().emplace(id, Job{.run = std::move(job), .owner = this});
    }

    // Kotlin drops a job posted after its scheduler closed; it stays in the table until `close`.
    context_->postNative(kolibri::getEnv(), static_cast<jlong>(id));
  }

  void JniScheduler::close() {
    if (closed_.exchange(true, std::memory_order_acq_rel)) {
      return;
    }

    // Destroyed outside the lock: a job's captures may take any lock when they die.
    std::vector<Job> dropped;
    {
      const std::lock_guard lock(jobsMutex());
      auto& table = jobs();
      for (auto it = table.begin(); it != table.end();) {
        if (it->second.owner == this) {
          dropped.push_back(std::move(it->second));
          it = table.erase(it);
        } else {
          ++it;
        }
      }
    }
  }

  void JniScheduler::run(const uint64_t id) {
    Job job;
    {
      const std::lock_guard lock(jobsMutex());
      auto& table = jobs();
      const auto found = table.find(id);
      if (found == table.end()) {
        return;
      }
      job = std::move(found->second);
      table.erase(found);
    }

    // Kotlin's `Runnable.run` is on the stack: nothing may propagate into it.
    try {
      job.run();
    } catch (const std::exception& error) {
      logLine(std::string("A native job failed: ") + error.what());
    } catch (...) {
      logLine("A native job failed");
    }
  }
} // namespace expo::modules::v2::async
