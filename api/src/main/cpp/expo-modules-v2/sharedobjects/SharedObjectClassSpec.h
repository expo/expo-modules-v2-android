#pragma once

#include <string>
#include <vector>

#include <expo-modules-v2/descriptor/EventSpec.h>
#include <expo-modules-v2/descriptor/HostFunctionSpec.h>
#include <expo-modules-v2/descriptor/HostPropertySpec.h>

namespace expo::modules::v2::sharedobjects {
  struct SharedObjectClassSpec {
    int classId = 0;

    std::string name;

    std::vector<descriptor::HostFunctionSpec> functions;
    std::vector<descriptor::HostPropertySpec> properties;
    std::vector<descriptor::EventSpec> events;

    void validateExportNames() const;
  };
}
