#pragma once

#include <string>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2::descriptor {
  struct EventSpec {
    std::string name;
    ExpectedType payloadType;
  };
} // namespace expo::modules::v2::descriptor
