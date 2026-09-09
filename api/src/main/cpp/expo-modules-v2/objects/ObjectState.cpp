#include <expo-modules-v2/objects/ObjectState.h>

#include <utility>

#include <kolibri/env.h>

#include <expo-modules-v2/objects/ObjectRegistry.h>

namespace expo::modules::v2::objects {
  ObjectState::ObjectState(
    const Kind kind,
    const ObjectId::Value objectId,
    kolibri::GlobalRef<> instance
  ) : instance_(std::move(instance)),
      kind_(kind),
      objectId_(objectId) {
  }

  ObjectState::~ObjectState() {
    // The last owner may be a JavaScript object Hermes finalizes on a JNI-detached GC thread. The
    // instance ref dies with this state's members, so attach here in case this is that thread.
    if (instance_) {
      kolibri::getEnv();
    }
    ObjectRegistry::forget(objectId_);
  }
} // namespace expo::modules::v2::objects
