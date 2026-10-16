#pragma once

#include <memory>
#include <optional>
#include <vector>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/internal/RuntimeLink.h>

namespace expo::modules::v2::cpp::detail {
  /**
   * `expo.modules`: serves the modules of a runtime by name. A name resolves to the host's own
   * module when it has one, and to the context's otherwise - the host that installed `expo` before
   * this runtime attached wins, because its modules are what the app was written against.
   */
  class ModuleHost final : public js::HostObject {
  public:
    ModuleHost(std::shared_ptr<RuntimeLink> link, std::optional<js::Object> hostModules);

    ~ModuleHost() override;

    /**
     * [object] as a `ModuleHost`, or null when it is some other host object. The build has no RTTI,
     * so a host object cannot be asked what it is: the hosts that exist are remembered instead.
     */
    [[nodiscard]] static std::shared_ptr<ModuleHost> from(const std::shared_ptr<js::HostObject>& object);

    /** The modules the host that installed `expo` first owns, if there were any. */
    [[nodiscard]] const std::optional<js::Object>& hostModules() const noexcept { return hostModules_; }

    js::Value get(js::Runtime& rt, const js::PropNameID& name) override;

    void set(js::Runtime& rt, const js::PropNameID& name, const js::Value& value) override;

    std::vector<js::PropNameID> getPropertyNames(js::Runtime& rt) override;

  private:
    /** Weak in effect: it says whether the runtime is still attached, and owns nothing of it. */
    std::shared_ptr<RuntimeLink> link_;
    std::optional<js::Object> hostModules_;
  };
} // namespace expo::modules::v2::cpp::detail
