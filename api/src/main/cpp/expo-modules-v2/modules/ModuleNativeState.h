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

namespace expo::modules::v2 {
  /**
   * The complete native state of a materialized JS module object (`expo.modules.<name>`). It owns
   * the module's ONE JNI global reference, function binders, and property binders. Each binder
   * caches its primary JVM method id only when the corresponding JS export is first invoked.
   * JS callbacks capture this state, so
   * no callback creates or stores another global reference. Keeping the metadata here also makes
   * the complete backing definition recoverable from the plain module object with
   * `ChainedNativeState::find<ModuleNativeState>`.
   *
   * It is a chain node, so further states can be attached to the same module object later without
   * replacing this one.
   */
  class ModuleNativeState : public ::expo::jsi::ChainedNativeStateOf<ModuleNativeState> {
  public:
    ModuleNativeState(
      kolibri::GlobalRef<> instance,
      std::vector<descriptor::HostFunctionSpec> functions,
      std::vector<descriptor::HostPropertySpec> properties
    );

    ~ModuleNativeState() override;

    [[nodiscard]] jobject instance() const { return instance_->get(); }

    [[nodiscard]] std::span<const FunctionBinder> functionBinders() const;

    [[nodiscard]] std::span<const PropertyBinder> propertyBinders() const;

  private:
    std::shared_ptr<kolibri::GlobalRef<>> instance_;
    std::vector<FunctionBinder> functionBinders_;
    std::vector<PropertyBinder> propertyBinders_;
  };
} // namespace expo::modules::v2
