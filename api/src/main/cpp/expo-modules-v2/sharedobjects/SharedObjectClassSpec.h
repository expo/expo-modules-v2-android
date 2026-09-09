#pragma once

#include <memory>
#include <string>
#include <vector>

#include <expo-modules-v2/descriptor/EventSpec.h>
#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

namespace expo::modules::v2::sharedobjects {
  struct SharedObjectClassSpec {
    int classId = 0;

    std::string name;

    struct Property {
      std::string name;
      std::shared_ptr<descriptor::HostFunctionSpec> getter;
      std::shared_ptr<descriptor::HostFunctionSpec> setter;
    };

    std::vector<std::shared_ptr<descriptor::HostFunctionSpec>> functions;
    std::vector<Property> properties;
    std::vector<descriptor::EventSpec> events;

    void validateExportNames() const;
  };
}
