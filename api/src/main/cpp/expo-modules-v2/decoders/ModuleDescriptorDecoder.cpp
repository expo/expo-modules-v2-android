#include <expo-modules-v2/decoders/ModuleDescriptorDecoder.h>

#include <stdexcept>
#include <utility>

#include <expo-modules-v2/decoders/FunctionDecoder.h>
#include <expo-modules-v2/decoders/PropertyDecoder.h>

namespace expo::modules::v2::decoders {
  descriptor::ModuleDescriptorPayload decodeModuleDescriptorPayload(
    kolibri::binary::Reader& reader
  ) {
    std::vector<descriptor::HostFunctionSpec> functions = decodeHostFunctionSpecs(reader);
    std::vector<descriptor::HostPropertySpec> properties = decodeHostPropertySpecs(reader);
    return {
      .functions = std::move(functions),
      .properties = std::move(properties)
    };
  }
} // namespace expo::modules::v2::decoders
