#pragma once

#include <jsi/jsi.h>

#include <memory>
#include <optional>
#include <string>

#include <kolibri/Ref.h>

#include <expo-modules-v2/binders/FunctionBinder.h>
#include <expo-modules-v2/descriptor/HostPropertySpec.h>

namespace expo::modules::v2 {
  class PropertyBinder {
  public:
    PropertyBinder(
      descriptor::HostPropertySpec spec,
      const kolibri::GlobalRef<>& instance
    );

    PropertyBinder(
      std::string name,
      const std::shared_ptr<descriptor::HostFunctionSpec>& getter,
      const std::shared_ptr<descriptor::HostFunctionSpec>& setter,
      const kolibri::GlobalRef<>& instance
    );

    [[nodiscard]] const std::string& name() const;

    [[nodiscard]] bool hasSetter() const;

    facebook::jsi::Function createGetter(facebook::jsi::Runtime& rt) const;

    facebook::jsi::Function createSetter(facebook::jsi::Runtime& rt) const;

    [[nodiscard]] facebook::jsi::Value get(facebook::jsi::Runtime& rt) const;

    void set(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) const;

  private:
    std::string name_;
    FunctionBinder getter_;
    std::optional<FunctionBinder> setter_;
  };
}
