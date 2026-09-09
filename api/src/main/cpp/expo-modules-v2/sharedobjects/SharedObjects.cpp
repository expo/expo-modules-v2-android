#include <expo-modules-v2/sharedobjects/SharedObjects.h>

#include <utility>

#include <expo-jsi/ChainedNativeState.h>
#include <kolibri/Ref.h>

#include <expo-modules-v2/jni/JSharedObjectRegistry.h>
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
      // Slow path, once per instance lifetime: the class id is the only thing Kotlin still answers.
      const int classId = JSharedObjectRegistry::classIdOf(env, instance);
      const SharedObjectClassSpec& spec = SharedObjectClassRegistry::get(classId);
      state = objects::ObjectRegistry::adopt(
        std::make_shared<SharedObjectState>(kolibri::GlobalRef<>::make(env, instance), objectId, spec)
      );
    }

    facebook::jsi::Object facade(rt);
    expo::jsi::ChainedNativeState::attach(
      rt,
      facade,
      std::make_shared<SharedObjectNativeState>(state)
    );

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

  std::shared_ptr<SharedObjectState> SharedObjects::stateOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& object
  ) {
    const auto attached = objects::ObjectNativeState::as<SharedObjectNativeState>(
      objects::ObjectNativeState::of(rt, object)
    );
    return attached == nullptr ? nullptr : attached->shared();
  }

} // namespace expo::modules::v2::sharedobjects
