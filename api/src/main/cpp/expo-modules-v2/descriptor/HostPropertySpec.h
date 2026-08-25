#pragma once

#include <optional>
#include <string>

#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

namespace expo::modules::v2::descriptor {
  /**
   * One decoded Kotlin property, expressed as accessor functions: the getter is a 0-arg function
   * returning the property type and the setter a 1-arg Unit function, so both ride the regular
   * function-invocation flow. Its getter and setter resolve independently on their first
   * read/write.
   */
  struct HostPropertySpec {
    std::string name;
    HostFunctionSpec getter;
    std::optional<HostFunctionSpec> setter;
  };
} // namespace expo::modules::v2::descriptor
