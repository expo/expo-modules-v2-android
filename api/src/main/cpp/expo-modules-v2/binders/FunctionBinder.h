#pragma once

#include <jsi/jsi.h>

#include <memory>
#include <string>

#include <kolibri/Ref.h>

#include <expo-modules-v2/JniMethodInvoker.h>
#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

namespace expo::modules::v2 {

  class FunctionBinder {
  public:
    /** [instance] is the owning state's ref; the binder is a member of that state and only points at it. */
    FunctionBinder(
      descriptor::HostFunctionSpec spec,
      const kolibri::GlobalRef<>& instance
    );

    FunctionBinder(
      std::shared_ptr<descriptor::HostFunctionSpec> spec,
      const kolibri::GlobalRef<>& instance
    );

    [[nodiscard]] const std::string& name() const;

    facebook::jsi::Value invoke(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value* args,
      size_t count
    ) const;

    [[nodiscard]] facebook::jsi::Function createFunction(facebook::jsi::Runtime& rt) const;

  private:
    [[nodiscard]] const descriptor::HostFunctionSpec& resolve() const;

    std::shared_ptr<descriptor::HostFunctionSpec> spec_;
    const kolibri::GlobalRef<>* instance_;

    FunctionInvoker invoker_;

    /**
     * The receiver's class, kept alive for as long as this binder so the spec can borrow it and call
     * the trampoline non-virtually. Filled by [resolve] alongside the method id.
     */
    mutable kolibri::GlobalRef<> declaringClass_;
  };
} // namespace expo::modules::v2
