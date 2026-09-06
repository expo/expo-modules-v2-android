#include <expo-modules-v2/modules/ModuleNativeState.h>

#include <kolibri/env.h>

namespace expo::modules::v2 {
  namespace {
    std::vector<FunctionBinder> makeFunctionBinders(
      const std::shared_ptr<kolibri::GlobalRef<>>& instance,
      std::vector<descriptor::HostFunctionSpec> specs
    ) {
      std::vector<FunctionBinder> binders;
      binders.reserve(specs.size());
      for (auto& spec: specs) {
        binders.emplace_back(std::move(spec), instance);
      }
      return binders;
    }

    std::vector<PropertyBinder> makePropertyBinders(
      const std::shared_ptr<kolibri::GlobalRef<>>& instance,
      std::vector<descriptor::HostPropertySpec> specs
    ) {
      std::vector<PropertyBinder> binders;
      binders.reserve(specs.size());
      for (auto& spec: specs) {
        binders.emplace_back(std::move(spec), instance);
      }
      return binders;
    }
  } // namespace

  ModuleNativeState::ModuleNativeState(
    kolibri::GlobalRef<> instance,
    std::vector<descriptor::HostFunctionSpec> functions,
    std::vector<descriptor::HostPropertySpec> properties,
    std::vector<descriptor::SharedClassSpec> sharedClasses
  ) : instance_(std::make_shared<kolibri::GlobalRef<>>(std::move(instance))),
      functionBinders_(makeFunctionBinders(instance_, std::move(functions))),
      propertyBinders_(makePropertyBinders(instance_, std::move(properties))),
      sharedClasses_(std::move(sharedClasses)) {
  }

  ModuleNativeState::~ModuleNativeState() {
    // A detached JS callback can be the last owner after ModulesHostObject is gone. Hermes may
    // release it on a JNI-detached GC thread. The binders share ownership of the instance ref and
    // are destroyed with this state, so attaching here covers whichever owner releases it last.
    if (*instance_) {
      kolibri::getEnv();
    }
  }

  std::span<const FunctionBinder> ModuleNativeState::functionBinders() const {
    return functionBinders_;
  }

  std::span<const PropertyBinder> ModuleNativeState::propertyBinders() const {
    return propertyBinders_;
  }

  std::span<descriptor::SharedClassSpec> ModuleNativeState::sharedClasses() {
    return sharedClasses_;
  }
} // namespace expo::modules::v2
