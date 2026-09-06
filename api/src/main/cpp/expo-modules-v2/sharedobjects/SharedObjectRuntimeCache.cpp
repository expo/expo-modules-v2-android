#include <expo-modules-v2/sharedobjects/SharedObjectRuntimeCache.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <utility>

#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectPrototype.h>
#include <expo-modules-v2/sharedobjects/SharedObjectState.h>

namespace expo::modules::v2::sharedobjects {
  namespace {
    std::mutex& registryMutex() {
      static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<const facebook::jsi::Runtime*, SharedObjectRuntimeCache*>& registry() {
      static std::unordered_map<const facebook::jsi::Runtime*, SharedObjectRuntimeCache*> caches;
      return caches;
    }

    // TODO(@lukmccall): It might be safe to remove generation for thread local cache
    std::atomic<uint64_t>& generation() {
      static std::atomic<uint64_t> value{0};
      return value;
    }

    struct Memo {
      const facebook::jsi::Runtime* runtime = nullptr;
      SharedObjectRuntimeCache* cache = nullptr;
      uint64_t generation = 0;
    };

    thread_local Memo tMemo;
  } // namespace

  SharedObjectRuntimeCache::SharedObjectRuntimeCache(facebook::jsi::Runtime& rt) : runtime_(&rt) {
    const std::lock_guard lock(registryMutex());
    registry()[runtime_] = this;
    generation().fetch_add(1, std::memory_order_release);
  }

  SharedObjectRuntimeCache::~SharedObjectRuntimeCache() {
    {
      const std::lock_guard lock(registryMutex());
      registry().erase(runtime_);
      generation().fetch_add(1, std::memory_order_release);
    }

    entries_.clear();
    prototypes_.clear();
  }

  SharedObjectRuntimeCache* SharedObjectRuntimeCache::find(
    const facebook::jsi::Runtime& rt
  ) noexcept {
    const uint64_t current = generation().load(std::memory_order_acquire);
    if (tMemo.runtime == &rt && tMemo.generation == current) {
      return tMemo.cache;
    }

    SharedObjectRuntimeCache* found = nullptr;
    {
      const std::lock_guard lock(registryMutex());
      const auto it = registry().find(&rt);
      found = it == registry().end() ? nullptr : it->second;
    }

    tMemo = Memo{.runtime = &rt, .cache = found, .generation = current};
    return found;
  }

  facebook::jsi::Value SharedObjectRuntimeCache::lookupFacade(
    facebook::jsi::Runtime& rt,
    const int objectId
  ) {
    const auto it = entries_.find(objectId);
    if (it == entries_.end()) {
      return facebook::jsi::Value::undefined();
    }

    const std::shared_ptr<SharedObjectState> state = it->second.state.lock();
    if (state == nullptr || state->released()) {
      entries_.erase(it);
      return facebook::jsi::Value::undefined();
    }

    facebook::jsi::Value cached = it->second.facade.lock(rt);
    if (cached.isUndefined()) {
      entries_.erase(it);
    }
    return cached;
  }

  void SharedObjectRuntimeCache::storeFacade(
    facebook::jsi::Runtime& rt,
    const int objectId,
    const facebook::jsi::Object& facade,
    const std::shared_ptr<SharedObjectState>& state
  ) {
    sweep(rt);

    const auto it = entries_.find(objectId);
    if (it != entries_.end()) {
      it->second.facade = facebook::jsi::WeakObject(rt, facade);
      it->second.state = state;
      return;
    }

    entries_.emplace(
      objectId,
      Entry{
        .facade = facebook::jsi::WeakObject(rt, facade),
        .state = state,
      }
    );
  }

  const facebook::jsi::Object& SharedObjectRuntimeCache::prototypeFor(
    facebook::jsi::Runtime& rt,
    const int classId,
    const bool installMembers
  ) {
    Prototype& prototype = prototypes_
      .try_emplace(classId, Prototype{.object = facebook::jsi::Object(rt)})
      .first->second;

    if (installMembers && !prototype.installed) {
      installPrototypeMembers(rt, prototype.object, SharedObjectClassRegistry::get(classId));
      prototype.installed = true;
    }

    return prototype.object;
  }

  void SharedObjectRuntimeCache::sweep(facebook::jsi::Runtime& rt) {
    if (entries_.size() < sweepThreshold_) {
      return;
    }

    for (auto it = entries_.begin(); it != entries_.end();) {
      if (it->second.facade.lock(rt).isUndefined()) {
        it = entries_.erase(it);
      } else {
        ++it;
      }
    }

    sweepThreshold_ = std::max(kMinSweepThreshold, entries_.size() * 2);
  }
} // namespace expo::modules::v2::sharedobjects
