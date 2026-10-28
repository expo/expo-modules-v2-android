#include <expo-modules-v2/sharedobjects/SharedObjects.h>

#include <utility>

#include <expo-jsi/ChainedNativeState.h>

#include <expo-modules-v2/jni/JSharedObject.h>
#include <expo-modules-v2/jni/JSharedObjectRegistry.h>
#include <expo-modules-v2/objects/InstanceRef.h>
#include <expo-modules-v2/objects/ObjectId.h>
#include <expo-modules-v2/objects/ObjectRegistry.h>
#include <expo-modules-v2/objects/RuntimeObjects.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectNativeState.h>
#include <expo-modules-v2/sharedobjects/SharedObjectState.h>

namespace expo::modules::v2::sharedobjects {
  facebook::jsi::Value SharedObjects::facadeFor(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject instance
  ) {
    const objects::ObjectId::Value objectId = objects::ObjectId::of(env, instance);

    objects::RuntimeObjects* table = objects::RuntimeObjects::find(rt);
    if (table != nullptr) {
      facebook::jsi::Value cached = table->lookup(rt, objectId);
      if (!cached.isUndefined()) {
        return cached;
      }
    }

    std::shared_ptr<SharedObjectState> state =
      objects::ObjectRegistry::find<SharedObjectState>(objectId);
    if (state == nullptr) {
      // Slow path, once per instance lifetime: the class id and the memory pressure are the only
      // things Kotlin still answers, and the pressure only when the class overrides it.
      const int classId = JSharedObjectRegistry::classIdOf(env, instance);
      const SharedObjectClassSpec& spec = SharedObjectClassRegistry::get(classId);
      const int pressure =
        spec.reportsMemoryPressure ? JSharedObject::additionalMemoryPressureOf(env, instance) : 0;
      state = objects::ObjectRegistry::adopt(
        std::make_shared<SharedObjectState>(
          objects::InstanceRef::pin(env, instance),
          objectId,
          spec,
          pressure > 0 ? static_cast<size_t>(pressure) : 0
        )
      );
    }

    facebook::jsi::Object facade(rt);
    expo::jsi::ChainedNativeState::attach(
      rt,
      facade,
      std::make_shared<SharedObjectNativeState>(state)
    );
    const size_t pressure = state->additionalMemoryPressure();
    if (pressure > 0) {
      facade.setExternalMemoryPressure(rt, pressure);
    }

    if (table != nullptr) {
      facade.setPrototype(
        rt,
        facebook::jsi::Value(
          rt,
          table->prototypeFor(rt, state->spec().classId, /* installMembers */ true)
        )
      );
      table->store(rt, objectId, facade);
    }

    return facebook::jsi::Value(rt, std::move(facade));
  }

  SharedObjectState* SharedObjects::stateOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& object
  ) {
    const objects::ObjectNativeState* node = objects::ObjectNativeState::borrow(rt, object);
    return node == nullptr ? nullptr : node->stateAs<SharedObjectState>();
  }

  SharedObjectState* SharedObjects::receiverStateOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& object
  ) {
    const objects::ObjectNativeState* node = objects::ObjectNativeState::borrowTrusted(rt, object);
    return node == nullptr ? nullptr : node->stateAs<SharedObjectState>();
  }

} // namespace expo::modules::v2::sharedobjects
