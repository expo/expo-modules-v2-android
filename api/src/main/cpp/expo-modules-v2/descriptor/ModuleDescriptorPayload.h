#pragma once

#include <vector>

#include <expo-modules-v2/descriptor/HostFunctionSpec.h>
#include <expo-modules-v2/descriptor/HostPropertySpec.h>

namespace expo::modules::v2::descriptor {
  struct ModuleDescriptorPayload {
    std::vector<HostFunctionSpec> functions;
    std::vector<HostPropertySpec> properties;
  };
} // namespace expo::modules::v2::descriptor
