#pragma once

#include <jni.h>

#include <memory>
#include <span>
#include <vector>

#include <expo-jsi/ChainedNativeState.h>
#include <kolibri/Ref.h>

#include <expo-modules-v2/binders/FunctionBinder.h>
#include <expo-modules-v2/binders/PropertyBinder.h>
#include <expo-modules-v2/descriptor/HostPropertySpec.h>
#include <expo-modules-v2/descriptor/SharedClassSpec.h>

namespace expo::modules::v2 {
  class ModuleNativeState : public ::expo::jsi::ChainedNativeStateOf<ModuleNativeState> {
  public:
    ModuleNativeState(
      kolibri::GlobalRef<> instance,
      std::vector<descriptor::HostFunctionSpec> functions,
      std::vector<descriptor::HostPropertySpec> properties,
      std::vector<descriptor::SharedClassSpec> sharedClasses
    );

    ~ModuleNativeState() override;

    [[nodiscard]] jobject instance() const { return instance_->get(); }

    [[nodiscard]] std::span<const FunctionBinder> functionBinders() const;

    [[nodiscard]] std::span<const PropertyBinder> propertyBinders() const;

    [[nodiscard]] std::span<descriptor::SharedClassSpec> sharedClasses();

  private:
    std::shared_ptr<kolibri::GlobalRef<>> instance_;
    std::vector<FunctionBinder> functionBinders_;
    std::vector<PropertyBinder> propertyBinders_;
    std::vector<descriptor::SharedClassSpec> sharedClasses_;
  };
} // namespace expo::modules::v2
