#include <expo-modules-v2/sharedobjects/SharedObjectState.h>

#include <utility>

#include <kolibri/env.h>

#include <expo-modules-v2/jni/JSharedObjectRegistry.h>

namespace expo::modules::v2::sharedobjects {
  SharedObjectState::SharedObjectState(
    kolibri::GlobalRef<> instance,
    const objects::ObjectId::Value objectId,
    const SharedObjectClassSpec& spec
  ) : ObjectState(kKind, objectId, std::move(instance)),
      spec_(&spec) {
  }

  SharedObjectState::~SharedObjectState() {
    release();
  }

  void SharedObjectState::release() {
    if (released_.exchange(true, std::memory_order_acq_rel)) {
      return;
    }

    if (!instance_) {
      return;
    }

    JNIEnv* env = kolibri::getEnv();
    JSharedObjectRegistry::release(env, instance_.get());
    instance_.reset();
  }
}
