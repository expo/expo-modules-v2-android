#include <expo-modules-v2/objects/RuntimeObjects.h>

#include <algorithm>
#include <atomic>
#include <mutex>

#include <kolibri/env.h>

#include <expo-modules-v2/async/AsyncRuntimeState.h>
#include <expo-modules-v2/events/EventEmitter.h>
#include <expo-modules-v2/jni/JEventSupport.h>
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

    // Kotlin was already told about these listeners by `dropAllListeners`; this only frees them.
    listeners_.clear();
    entries_.clear();
    prototypes_.clear();
    eventEmitterPrototype_.reset();
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
      dropListeners(rt, objectId);
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
    auto found = prototypes_.find(classId);
    if (found == prototypes_.end()) {
      // Looked up first: `try_emplace` would build the object before knowing whether it is needed.
      facebook::jsi::Object object(rt);
      // A class extends the emitter, as `SharedObject extends EventEmitter` does in JavaScript.
      object.setPrototype(rt, facebook::jsi::Value(rt, eventEmitterPrototype(rt)));
      found = prototypes_.emplace(classId, Prototype{.object = std::move(object)}).first;
    }
    Prototype& prototype = found->second;

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

  const facebook::jsi::Object& RuntimeObjects::eventEmitterPrototype(facebook::jsi::Runtime& rt) {
    if (!eventEmitterPrototype_.has_value()) {
      facebook::jsi::Object prototype(rt);
      events::installEmitterMethods(rt, prototype);
      eventEmitterPrototype_.emplace(std::move(prototype));
    }
    return *eventEmitterPrototype_;
  }

  void RuntimeObjects::sweep(facebook::jsi::Runtime& rt) {
    if (entries_.size() < sweepThreshold_) {
      return;
    }

    for (auto it = entries_.begin(); it != entries_.end();) {
      if (it->second.lock(rt).isUndefined()) {
        const ObjectId::Value objectId = it->first;
        it = entries_.erase(it);
        dropListeners(rt, objectId);
      } else {
        ++it;
      }
    }

    sweepThreshold_ = std::max(kMinSweepThreshold, entries_.size() * 2);
  }

  namespace {
    /** Tells Kotlin that [rt] no longer observes event [index] on [instance], if Kotlin can still hear it. */
    auto stopObserving(facebook::jsi::Runtime& rt) {
      // Null once the async state is gone, which only happens after `dropAllListeners` ran.
      const async::AsyncRuntimeState* async = async::AsyncRuntimeState::find(rt);
      const jobject context = async == nullptr ? nullptr : async->context();
      JNIEnv* env = kolibri::getEnv();

      return [env, context](const jobject instance, const int index) {
        if (context != nullptr && instance != nullptr) {
          JEventSupport::observe(env, instance, index, context, false);
        }
      };
    }
  } // namespace

  void RuntimeObjects::dropListeners(facebook::jsi::Runtime& rt, const ObjectId::Value objectId) {
    if (!listeners_.has(objectId)) {
      return;
    }
    listeners_.drop(objectId, stopObserving(rt));
  }

  void RuntimeObjects::dropAllListeners(facebook::jsi::Runtime& rt) {
    listeners_.dropAll(stopObserving(rt));
  }
} // namespace expo::modules::v2::objects
