#include <expo-modules-v2/objects/RuntimeObjects.h>

#include <algorithm>
#include <atomic>
#include <mutex>

#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectPrototype.h>

namespace expo::modules::v2::objects {
  namespace {
    std::mutex& registryMutex() {
      static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<const facebook::jsi::Runtime*, RuntimeObjects*>& registry() {
      static std::unordered_map<const facebook::jsi::Runtime*, RuntimeObjects*> tables;
      return tables;
    }

    // TODO(@lukmccall): It might be safe to remove generation for thread local cache
    std::atomic<uint64_t>& generation() {
      static std::atomic<uint64_t> value{0};
      return value;
    }

    struct Memo {
      const facebook::jsi::Runtime* runtime = nullptr;
      RuntimeObjects* table = nullptr;
      uint64_t generation = 0;
    };

    thread_local Memo tMemo;
  } // namespace

  RuntimeObjects::RuntimeObjects(facebook::jsi::Runtime& rt) : runtime_(&rt) {
    const std::lock_guard lock(registryMutex());
    registry()[runtime_] = this;
    generation().fetch_add(1, std::memory_order_release);
  }

  RuntimeObjects::~RuntimeObjects() {
    {
      const std::lock_guard lock(registryMutex());
      registry().erase(runtime_);
      generation().fetch_add(1, std::memory_order_release);
    }

    entries_.clear();
    prototypes_.clear();
  }

  RuntimeObjects* RuntimeObjects::find(const facebook::jsi::Runtime& rt) noexcept {
    const uint64_t current = generation().load(std::memory_order_acquire);
    if (tMemo.runtime == &rt && tMemo.generation == current) {
      return tMemo.table;
    }

    RuntimeObjects* found = nullptr;
    {
      const std::lock_guard lock(registryMutex());
      const auto it = registry().find(&rt);
      found = it == registry().end() ? nullptr : it->second;
    }

    tMemo = Memo{.runtime = &rt, .table = found, .generation = current};
    return found;
  }

  facebook::jsi::Value RuntimeObjects::lookup(
    facebook::jsi::Runtime& rt,
    const ObjectId::Value objectId
  ) {
    const auto it = entries_.find(objectId);
    if (it == entries_.end()) {
      return facebook::jsi::Value::undefined();
    }

    facebook::jsi::Value cached = it->second.lock(rt);
    if (cached.isUndefined()) {
      entries_.erase(it);
    }
    return cached;
  }

  void RuntimeObjects::store(
    facebook::jsi::Runtime& rt,
    const ObjectId::Value objectId,
    const facebook::jsi::Object& object
  ) {
    sweep(rt);
    entries_.insert_or_assign(objectId, facebook::jsi::WeakObject(rt, object));
  }

  const facebook::jsi::Object& RuntimeObjects::prototypeFor(
    facebook::jsi::Runtime& rt,
    const int classId,
    const bool installMembers
  ) {
    Prototype& prototype = prototypes_
      .try_emplace(classId, Prototype{.object = facebook::jsi::Object(rt)})
      .first->second;

    if (installMembers && !prototype.installed) {
      sharedobjects::installPrototypeMembers(
        rt,
        prototype.object,
        sharedobjects::SharedObjectClassRegistry::get(classId)
      );
      prototype.installed = true;
    }

    return prototype.object;
  }

  void RuntimeObjects::sweep(facebook::jsi::Runtime& rt) {
    if (entries_.size() < sweepThreshold_) {
      return;
    }

    for (auto it = entries_.begin(); it != entries_.end();) {
      if (it->second.lock(rt).isUndefined()) {
        it = entries_.erase(it);
      } else {
        ++it;
      }
    }

    sweepThreshold_ = std::max(kMinSweepThreshold, entries_.size() * 2);
  }
} // namespace expo::modules::v2::objects
