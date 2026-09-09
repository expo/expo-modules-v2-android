#include <expo-modules-v2/objects/ObjectRegistry.h>

#include <mutex>
#include <unordered_map>
#include <utility>

namespace expo::modules::v2::objects {
  namespace {
    std::mutex& registryMutex() {
      static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<ObjectId::Value, std::weak_ptr<ObjectState>>& states() {
      static std::unordered_map<ObjectId::Value, std::weak_ptr<ObjectState>> map;
      return map;
    }
  } // namespace

  std::shared_ptr<ObjectState> ObjectRegistry::find(const ObjectId::Value objectId) {
    const std::lock_guard lock(registryMutex());
    const auto it = states().find(objectId);
    return it == states().end() ? nullptr : it->second.lock();
  }

  std::shared_ptr<ObjectState> ObjectRegistry::adopt(std::shared_ptr<ObjectState> created) {
    const std::lock_guard lock(registryMutex());
    std::weak_ptr<ObjectState>& slot = states()[created->objectId()];
    if (std::shared_ptr<ObjectState> existing = slot.lock()) {
      return existing;
    }
    slot = created;
    return created;
  }

  void ObjectRegistry::forget(const ObjectId::Value objectId) {
    const std::lock_guard lock(registryMutex());
    if (const auto it = states().find(objectId); it != states().end() && it->second.expired()) {
      states().erase(it);
    }
  }
} // namespace expo::modules::v2::objects
