#include <expo-modules-v2/modules/ModuleNativeState.h>

#include <utility>

#include <kolibri/env.h>

namespace expo::modules::v2 {
  namespace {
    std::vector<FunctionBinder> makeFunctionBinders(
      const kolibri::GlobalRef<>& instance,
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
      const kolibri::GlobalRef<>& instance,
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
    std::shared_ptr<ModuleState> state,
    std::vector<descriptor::HostFunctionSpec> functions,
    std::vector<descriptor::HostPropertySpec> properties,
    std::vector<descriptor::SharedClassSpec> sharedClasses
  ) : ObjectNativeState(std::move(state)),
      functionBinders_(makeFunctionBinders(this->state()->instanceRef(), std::move(functions))),
      propertyBinders_(makePropertyBinders(this->state()->instanceRef(), std::move(properties))),
      sharedClasses_(std::move(sharedClasses)) {
  }

  ModuleNativeState::~ModuleNativeState() {
    // A detached JS callback can be the last owner after ModulesHostObject is gone, and Hermes may
    // release it on a JNI-detached GC thread. The binders die with this node and each may hold a
    // global ref (its resolved declaring class), so attach before the members go.
    kolibri::getEnv();
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
