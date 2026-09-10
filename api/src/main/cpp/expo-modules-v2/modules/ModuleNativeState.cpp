#include <expo-modules-v2/modules/ModuleNativeState.h>

#include <utility>

namespace expo::modules::v2 {
  ModuleNativeState::ModuleNativeState(
    std::shared_ptr<ModuleState> state,
    std::vector<descriptor::HostFunctionSpec> functions,
    std::vector<descriptor::HostPropertySpec> properties,
    std::vector<descriptor::SharedClassSpec> sharedClasses,
    std::vector<descriptor::EventSpec> events
  ) : ObjectNativeState(std::move(state)),
      functions_(std::move(functions)),
      properties_(std::move(properties)),
      sharedClasses_(std::move(sharedClasses)),
      events_(std::move(events)) {
  }

  const ModuleState& ModuleNativeState::moduleState() const {
    return static_cast<const ModuleState&>(*state());
  }

  std::span<const descriptor::HostFunctionSpec> ModuleNativeState::functions() const {
    return functions_;
  }

  std::span<const descriptor::HostPropertySpec> ModuleNativeState::properties() const {
    return properties_;
  }

  std::span<const descriptor::SharedClassSpec> ModuleNativeState::sharedClasses() const {
    return sharedClasses_;
  }

  std::span<const descriptor::EventSpec> ModuleNativeState::events() const {
    return events_;
  }
} // namespace expo::modules::v2
