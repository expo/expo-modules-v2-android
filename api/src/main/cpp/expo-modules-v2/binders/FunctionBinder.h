#pragma once

#include <jsi/jsi.h>

#include <memory>
#include <string>

#include <kolibri/Ref.h>

#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

namespace expo::modules::v2 {
  class ModuleNativeState;

  class FunctionBinder {
  public:
    FunctionBinder(descriptor::HostFunctionSpec spec, std::shared_ptr<kolibri::GlobalRef<>> instance);

    [[nodiscard]] const std::string& name() const;

    facebook::jsi::Function createFunction(facebook::jsi::Runtime& rt) const;

  private:
    [[nodiscard]] const descriptor::HostFunctionSpec& resolve() const;

    std::shared_ptr<descriptor::HostFunctionSpec> spec_;
    std::shared_ptr<kolibri::GlobalRef<>> instance_;

    /**
     * The receiver's class, kept alive for as long as this binder so the spec can borrow it and call
     * the trampoline non-virtually. Filled by [resolve] alongside the method id.
     */
    mutable kolibri::GlobalRef<> declaringClass_;
  };
} // namespace expo::modules::v2
