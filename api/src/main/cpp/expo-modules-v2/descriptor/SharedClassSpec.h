#pragma once

#include <memory>
#include <optional>
#include <string>

#include <expo-modules-v2/binders/StaticFunctionBinder.h>
#include <expo-modules-v2/descriptor/StaticFunctionSpec.h>

namespace expo::modules::v2::descriptor {
  struct SharedClassSpec {
    std::string jsName;

    int classId = 0;

    std::shared_ptr<StaticFunctionSpec> constructor;

    [[nodiscard]] const StaticFunctionBinder& binder();

  private:
    std::optional<StaticFunctionBinder> binder_;
  };
}
