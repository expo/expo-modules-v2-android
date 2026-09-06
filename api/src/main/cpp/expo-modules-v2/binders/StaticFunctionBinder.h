#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <memory>
#include <string>

#include <expo-modules-v2/JniMethodInvoker.h>
#include <expo-modules-v2/descriptor/StaticFunctionSpec.h>

namespace expo::modules::v2 {
  class StaticFunctionBinder {
  public:
    StaticFunctionBinder(std::shared_ptr<descriptor::StaticFunctionSpec> spec, jclass declaringClass);

    [[nodiscard]] const std::string& name() const;

    facebook::jsi::Value invoke(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value* args,
      size_t count
    ) const;

    [[nodiscard]] facebook::jsi::Function createFunction(facebook::jsi::Runtime& rt) const;

  private:
    [[nodiscard]] const descriptor::StaticFunctionSpec& resolve() const;

    std::shared_ptr<descriptor::StaticFunctionSpec> spec_;

    jclass declaringClass_;

    FunctionInvoker invoker_;
  };
} // namespace expo::modules::v2
