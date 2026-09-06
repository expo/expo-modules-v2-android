#include <expo-modules-v2/sharedobjects/SharedObjects.h>

#include <mutex>
#include <unordered_map>
#include <utility>

#include <expo-jsi/ChainedNativeState.h>
#include <kolibri/Ref.h>

#include <expo-modules-v2/jni/JSharedObjectRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectNativeState.h>
#include <expo-modules-v2/sharedobjects/SharedObjectRuntimeCache.h>
#include <expo-modules-v2/sharedobjects/SharedObjectState.h>

namespace expo::modules::v2::sharedobjects {
  namespace {
    std::mutex& tableMutex() {
      static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<int, std::weak_ptr<SharedObjectState>>& table() {
      static std::unordered_map<int, std::weak_ptr<SharedObjectState>> states;
      return states;
    }

    std::shared_ptr<SharedObjectState> lookupState(const int objectId) {
      const std::lock_guard lock(tableMutex());
      const auto it = table().find(objectId);
      return it == table().end() ? nullptr : it->second.lock();
    }
  } // namespace

  facebook::jsi::Value SharedObjects::facadeFor(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject instance
  ) {
    const JSharedObjectRegistry::Attachment attachment =
      JSharedObjectRegistry::attach(env, instance);

    SharedObjectRuntimeCache* cache = SharedObjectRuntimeCache::find(rt);
    if (cache != nullptr) {
      facebook::jsi::Value cached = cache->lookupFacade(rt, attachment.objectId);
      if (!cached.isUndefined()) {
        return cached;
      }
    }

    std::shared_ptr<SharedObjectState> state = lookupState(attachment.objectId);
    if (state == nullptr) {
      const SharedObjectClassSpec& spec = SharedObjectClassRegistry::get(attachment.classId);
      auto created = std::make_shared<SharedObjectState>(
        kolibri::GlobalRef<>::make(env, instance),
        attachment.objectId,
        spec
      );

      const std::lock_guard lock(tableMutex());
      const auto it = table().find(attachment.objectId);
      state = it == table().end() ? nullptr : it->second.lock();
      if (state == nullptr) {
        state = std::move(created);
        table()[attachment.objectId] = state;
      }
    }

    facebook::jsi::Object facade(rt);
    expo::jsi::ChainedNativeState::attach(
      rt,
      facade,
      std::make_shared<SharedObjectNativeState>(state)
    );

    if (cache != nullptr) {
      facade.setPrototype(
        rt,
        facebook::jsi::Value(
          rt,
          cache->prototypeFor(rt, attachment.classId, /* installMembers */ true)
        )
      );
      cache->storeFacade(rt, attachment.objectId, facade, state);
    }

    return facebook::jsi::Value(rt, std::move(facade));
  }

  std::shared_ptr<SharedObjectState> SharedObjects::stateOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& object
  ) {
    const auto attached = expo::jsi::ChainedNativeState::find<SharedObjectNativeState>(rt, object);
    return attached == nullptr ? nullptr : attached->state();
  }

  void SharedObjects::forget(const int objectId) {
    const std::lock_guard lock(tableMutex());
    if (const auto it = table().find(objectId); it != table().end() && it->second.expired()) {
      table().erase(it);
    }
  }
} // namespace expo::modules::v2::sharedobjects
