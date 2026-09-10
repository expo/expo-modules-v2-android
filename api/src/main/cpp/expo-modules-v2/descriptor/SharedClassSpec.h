#pragma once

#include <string>

#include <expo-modules-v2/descriptor/StaticFunctionSpec.h>

namespace expo::modules::v2::descriptor {
  struct SharedClassSpec {
    std::string jsName;

    int classId = 0;

    StaticFunctionSpec constructor;
  };
}
