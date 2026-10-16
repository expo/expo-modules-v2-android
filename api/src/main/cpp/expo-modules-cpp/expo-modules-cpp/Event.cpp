#include <expo-modules-cpp/Event.h>

#include <algorithm>
#include <stdexcept>

#include <expo-modules-cpp/internal/Log.h>

namespace expo::modules::v2::cpp::detail {
  namespace {
    /**
     * Runs a start or stop hook. They are native code's to write and run from places that cannot
     * take an exception - a destructor, a sweep, a lookup - so one that throws is logged, and the
     * listener that triggered it is registered or removed all the same.
     */
    void runHook(const std::function<void()>& hook, const std::string& event, const char* name) noexcept {
      try {
        hook();
      } catch (const std::exception& error) {
        logError(std::string(name) + " of '" + event + "' failed: " + error.what());
      } catch (...) {
        logError(std::string(name) + " of '" + event + "' failed");
      }
    }
  } // namespace

  EventBase::EventBase(NativeObject* const owner, std::string name, EventHooks hooks)
    : owner_(owner), name_(std::move(name)), index_(static_cast<uint32_t>(owner->events_.size())), hooks_(std::move(hooks)) {
    const bool taken = std::ranges::any_of(owner->events_, [&](const EventBase* each) { return each->name_ == name_; });
    if (taken) {
      throw std::invalid_argument("An object declares two events named '" + name_ + "'");
    }
    owner->events_.push_back(this);
  }

  EventBase::~EventBase() = default;

  void EventBase::attach(const std::shared_ptr<RuntimeLink>& link) {
    bool first;
    {
      const std::lock_guard lock(mutex_);
      if (std::ranges::find(observers_, link) != observers_.end()) {
        return;
      }
      observers_.push_back(link);
      observed_.store(static_cast<uint32_t>(observers_.size()), std::memory_order_release);
      first = observers_.size() == 1;
    }
    if (first && hooks_.onStartObserving) {
      runHook(hooks_.onStartObserving, name_, "onStartObserving");
    }
  }

  void EventBase::detach(const RuntimeLink* const link) {
    bool last;
    {
      const std::lock_guard lock(mutex_);
      const auto found = std::ranges::find_if(observers_, [link](const auto& each) { return each.get() == link; });
      if (found == observers_.end()) {
        return;
      }
      observers_.erase(found);
      observed_.store(static_cast<uint32_t>(observers_.size()), std::memory_order_release);
      last = observers_.empty();
    }
    if (last && hooks_.onStopObserving) {
      runHook(hooks_.onStopObserving, name_, "onStopObserving");
    }
  }

  void EventBase::detachAll() {
    bool hadObservers;
    {
      const std::lock_guard lock(mutex_);
      hadObservers = !observers_.empty();
      observers_.clear();
      observed_.store(0, std::memory_order_release);
    }
    if (hadObservers && hooks_.onStopObserving) {
      runHook(hooks_.onStopObserving, name_, "onStopObserving");
    }
  }

  std::vector<std::shared_ptr<RuntimeLink>> EventBase::observers() const {
    const std::lock_guard lock(mutex_);
    return observers_;
  }
} // namespace expo::modules::v2::cpp::detail
