#pragma once

#include <memory>
#include <span>
#include <vector>

#include <expo-modules-v2/descriptor/EventSpec.h>
#include <expo-modules-v2/descriptor/HostFunctionSpec.h>
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
      std::vector<descriptor::SharedClassSpec> sharedClasses,
      std::vector<descriptor::EventSpec> events
    );

    [[nodiscard]] const ModuleState& moduleState() const;

    [[nodiscard]] std::span<const descriptor::EventSpec> events() const override;

    [[nodiscard]] std::span<const descriptor::HostFunctionSpec> functions() const;

    [[nodiscard]] std::span<const descriptor::HostPropertySpec> properties() const;

    [[nodiscard]] std::span<const descriptor::SharedClassSpec> sharedClasses() const;

  private:
    std::vector<descriptor::HostFunctionSpec> functions_;
    std::vector<descriptor::HostPropertySpec> properties_;
    std::vector<descriptor::SharedClassSpec> sharedClasses_;
    std::vector<descriptor::EventSpec> events_;
  };
} // namespace expo::modules::v2
