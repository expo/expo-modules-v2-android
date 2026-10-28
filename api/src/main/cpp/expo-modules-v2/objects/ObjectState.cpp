#include <expo-modules-v2/objects/ObjectState.h>

#include <utility>

#include <expo-modules-v2/objects/ObjectRegistry.h>

namespace expo::modules::v2::objects {
  ObjectState::ObjectState(
    const Kind kind,
    const ObjectId::Value objectId,
    InstanceRef instance
  ) : instance_(std::move(instance)),
      kind_(kind),
      objectId_(objectId) {
  }

  ObjectState::~ObjectState() {
    ObjectRegistry::forget(objectId_);
  }
} // namespace expo::modules::v2::objects
