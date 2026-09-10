#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>

#include <stdexcept>
#include <string_view>
#include <unordered_set>

#include <expo-modules-v2/events/EventEmitter.h>

namespace expo::modules::v2::sharedobjects {
  void SharedObjectClassSpec::validateExportNames() const {
    std::unordered_set<std::string_view> names;
    names.reserve(functions.size() + properties.size() + events.size());

    for (const descriptor::HostFunctionSpec& function: functions) {
      names.emplace(function.name);
    }
    for (const descriptor::HostPropertySpec& property: properties) {
      names.emplace(property.name);
    }
    for (const descriptor::EventSpec& event: events) {
      names.emplace(event.name);
    }

    if (names.size() != functions.size() + properties.size() + events.size()) {
      throw std::invalid_argument(
        "Shared object class '" + name + "' declares the same export name twice"
      );
    }

    // The prototype defines these itself, non-configurable, so a second definition would throw
    // halfway through installing the class.
    for (const std::string_view exportName: names) {
      if (events::isEmitterMemberName(exportName)) {
        throw std::invalid_argument(
          "Shared object class '" + name + "' exports '" + std::string(exportName) +
          "', which is reserved for the event emitter"
        );
      }
    }
  }
}
