#include <expo-modules-v2/decoders/ModuleDescriptorDecoder.h>

#include <utility>

#include <expo-modules-v2/decoders/EventDecoder.h>
#include <expo-modules-v2/decoders/FunctionDecoder.h>
#include <expo-modules-v2/decoders/PropertyDecoder.h>
#include <expo-modules-v2/decoders/SharedClassDecoder.h>

namespace expo::modules::v2::decoders {
  descriptor::ModuleDescriptorPayload decodeModuleDescriptorPayload(
    kolibri::binary::Reader& reader,
    const jclass declaringClass
  ) {
    std::vector<descriptor::HostFunctionSpec> functions = decodeHostFunctionSpecs(reader, declaringClass);
    std::vector<descriptor::HostPropertySpec> properties = decodeHostPropertySpecs(reader, declaringClass);
    std::vector<descriptor::SharedClassSpec> sharedClasses = decodeSharedClassSpecs(reader);
    std::vector<descriptor::EventSpec> events = decodeEventSpecs(reader);

    return {
      .functions = std::move(functions),
      .properties = std::move(properties),
      .sharedClasses = std::move(sharedClasses),
      .events = std::move(events)
    };
  }
}
