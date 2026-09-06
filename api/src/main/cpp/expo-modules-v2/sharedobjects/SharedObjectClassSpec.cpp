#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>

#include <stdexcept>
#include <string_view>
#include <unordered_set>

namespace expo::modules::v2::sharedobjects {
  void SharedObjectClassSpec::validateExportNames() const {
    std::unordered_set<std::string_view> names;
    names.reserve(functions.size() + properties.size());

    for (const std::shared_ptr<descriptor::HostFunctionSpec>& function: functions) {
      names.emplace(function->name);
    }
    for (const Property& property: properties) {
      names.emplace(property.name);
    }

    if (names.size() != functions.size() + properties.size()) {
      throw std::invalid_argument(
        "Shared object class '" + name + "' declares the same export name twice"
      );
    }
  }
}
