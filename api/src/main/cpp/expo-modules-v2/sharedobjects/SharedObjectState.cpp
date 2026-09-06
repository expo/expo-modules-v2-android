#include <expo-modules-v2/sharedobjects/SharedObjectState.h>

#include <utility>

#include <kolibri/env.h>

#include <expo-modules-v2/jni/JSharedObjectRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjects.h>

namespace expo::modules::v2::sharedobjects {
  namespace {
    std::vector<FunctionBinder> makeFunctionBinders(
      const std::shared_ptr<kolibri::GlobalRef<>>& instance,
      const std::vector<std::shared_ptr<descriptor::HostFunctionSpec>>& specs
    ) {
      std::vector<FunctionBinder> binders;
      binders.reserve(specs.size());
      for (const std::shared_ptr<descriptor::HostFunctionSpec>& spec: specs) {
        binders.emplace_back(spec, instance);
      }
      return binders;
    }

    std::vector<PropertyBinder> makePropertyBinders(
      const std::shared_ptr<kolibri::GlobalRef<>>& instance,
      const std::vector<SharedObjectClassSpec::Property>& specs
    ) {
      std::vector<PropertyBinder> binders;
      binders.reserve(specs.size());
      for (const SharedObjectClassSpec::Property& spec: specs) {
        binders.emplace_back(spec.name, spec.getter, spec.setter, instance);
      }
      return binders;
    }
  } // namespace

  SharedObjectState::SharedObjectState(
    kolibri::GlobalRef<> instance,
    const int objectId,
    const SharedObjectClassSpec& spec
  ) : objectId_(objectId),
      spec_(&spec),
      instance_(std::make_shared<kolibri::GlobalRef<>>(std::move(instance))),
      functionBinders_(makeFunctionBinders(instance_, spec.functions)),
      propertyBinders_(makePropertyBinders(instance_, spec.properties)) {
  }

  SharedObjectState::~SharedObjectState() {
    release();
    SharedObjects::forget(objectId_);
  }

  void SharedObjectState::release() {
    if (released_.exchange(true, std::memory_order_acq_rel)) {
      return;
    }

    if (!instance_ || !*instance_) {
      return;
    }

    JNIEnv* env = kolibri::getEnv();
    JSharedObjectRegistry::release(env, instance_->get());
    instance_->reset();
  }
}
