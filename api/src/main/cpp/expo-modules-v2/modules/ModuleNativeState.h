#pragma once

#include <memory>
#include <span>
#include <vector>

#include <expo-modules-v2/binders/FunctionBinder.h>
#include <expo-modules-v2/binders/PropertyBinder.h>
#include <expo-modules-v2/descriptor/HostPropertySpec.h>
#include <expo-modules-v2/descriptor/SharedClassSpec.h>
#include <expo-modules-v2/modules/ModuleState.h>
#include <expo-modules-v2/objects/ObjectNativeState.h>

namespace expo::modules::v2 {
  /** The chain node on one module object: its export table, bound to the instance's `ModuleState`. */
  class ModuleNativeState final : public objects::ObjectNativeState {
  public:
    static constexpr Kind kKind = Kind::Module;

    ModuleNativeState(
      std::shared_ptr<ModuleState> state,
      std::vector<descriptor::HostFunctionSpec> functions,
      std::vector<descriptor::HostPropertySpec> properties,
      std::vector<descriptor::SharedClassSpec> sharedClasses
    );

    ~ModuleNativeState() override;

    [[nodiscard]] std::span<const FunctionBinder> functionBinders() const;

    [[nodiscard]] std::span<const PropertyBinder> propertyBinders() const;

    [[nodiscard]] std::span<descriptor::SharedClassSpec> sharedClasses();

  private:
    std::vector<FunctionBinder> functionBinders_;
    std::vector<PropertyBinder> propertyBinders_;
    std::vector<descriptor::SharedClassSpec> sharedClasses_;
  };
} // namespace expo::modules::v2
