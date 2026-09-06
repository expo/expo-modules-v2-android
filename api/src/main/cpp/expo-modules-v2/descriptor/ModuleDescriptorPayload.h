#pragma once

#include <vector>

#include <expo-modules-v2/descriptor/HostFunctionSpec.h>
#include <expo-modules-v2/descriptor/HostPropertySpec.h>
#include <expo-modules-v2/descriptor/SharedClassSpec.h>

namespace expo::modules::v2::descriptor {
  struct ModuleDescriptorPayload {
    std::vector<HostFunctionSpec> functions;
    std::vector<HostPropertySpec> properties;
    std::vector<SharedClassSpec> sharedClasses;
  };
} // namespace expo::modules::v2::descriptor
