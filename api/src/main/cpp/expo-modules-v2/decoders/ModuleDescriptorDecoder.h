#pragma once

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/descriptor/ModuleDescriptorPayload.h>

namespace expo::modules::v2::decoders {
  [[nodiscard]] descriptor::ModuleDescriptorPayload decodeModuleDescriptorPayload(
    kolibri::binary::Reader& reader
  );
} // namespace expo::modules::v2::decoders
