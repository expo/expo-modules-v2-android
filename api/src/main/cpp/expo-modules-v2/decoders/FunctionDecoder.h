#pragma once

#include <vector>

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

namespace expo::modules::v2::decoders {
  [[nodiscard]] descriptor::HostFunctionSpec decodeHostFunctionSpec(
    kolibri::binary::Reader& reader
  );

  [[nodiscard]] std::vector<descriptor::HostFunctionSpec> decodeHostFunctionSpecs(
    kolibri::binary::Reader& reader
  );
} // namespace expo::modules::v2::decoders
