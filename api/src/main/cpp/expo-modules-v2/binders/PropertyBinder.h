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
      std::shared_ptr<kolibri::GlobalRef<>> instance
    );

    [[nodiscard]] const std::string& name() const;

    [[nodiscard]] bool hasSetter() const;

    facebook::jsi::Function createGetter(facebook::jsi::Runtime& rt) const;

    facebook::jsi::Function createSetter(facebook::jsi::Runtime& rt) const;

  private:
    std::string name_;
    FunctionBinder getter_;
    std::optional<FunctionBinder> setter_;
  };
}
