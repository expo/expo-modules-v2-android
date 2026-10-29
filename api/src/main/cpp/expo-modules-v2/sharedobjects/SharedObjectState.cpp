#include <expo-modules-v2/sharedobjects/SharedObjectState.h>

#include <utility>

#include <kolibri/env.h>

#include <expo-modules-v2/jni/JSharedObjectRegistry.h>

namespace expo::modules::v2::sharedobjects {
  SharedObjectState::SharedObjectState(
    objects::InstanceRef instance,
    const objects::ObjectId::Value objectId,
    const SharedObjectClassSpec& spec,
    const size_t additionalMemoryPressure
  ) : ObjectState(kKind, objectId, std::move(instance)),
      spec_(&spec),
      additionalMemoryPressure_(additionalMemoryPressure) {
  }

  SharedObjectState::~SharedObjectState() {
    release();
  }

  facebook::jsi::Value SharedObjectState::invokeFunctionInSlot(
    facebook::jsi::Runtime& rt,
    const uint32_t index,
    const facebook::jsi::Value* args,
    const size_t count
  ) const {
    const objects::ScopedInstance receiver(instance_, objects::ScopedInstance::Promote::Yes);
    return spec_->functions[index].invoke(rt, receiver.get(), args, count);
  }

  facebook::jsi::Value SharedObjectState::getPropertyInSlot(
    facebook::jsi::Runtime& rt,
    const uint32_t index
  ) const {
    const objects::ScopedInstance receiver(instance_, objects::ScopedInstance::Promote::Yes);
    return spec_->properties[index].get(rt, receiver.get());
  }

  void SharedObjectState::setPropertyInSlot(
    facebook::jsi::Runtime& rt,
    const uint32_t index,
    const facebook::jsi::Value& value
  ) const {
    const objects::ScopedInstance receiver(instance_, objects::ScopedInstance::Promote::Yes);
    spec_->properties[index].set(rt, receiver.get(), value);
  }

  void SharedObjectState::release() {
    if (released_.exchange(true, std::memory_order_acq_rel)) {
      return;
    }

    if (!instance_) {
      return;
    }

    if (auto* global = instance_.global(); global != nullptr) {
      JSharedObjectRegistry::release(kolibri::getEnv(), global);
    } else {
      const objects::ScopedInstance instance(instance_);
      JSharedObjectRegistry::release(kolibri::getEnv(), instance.get());
    }

    instance_.reset();
  }
}
