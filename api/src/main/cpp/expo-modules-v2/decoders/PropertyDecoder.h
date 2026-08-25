#pragma once

#include <vector>

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/descriptor/HostPropertySpec.h>

namespace expo::modules::v2::decoders {
  [[nodiscard]] descriptor::HostPropertySpec decodeHostPropertySpec(
    kolibri::binary::Reader& reader
  );

  [[nodiscard]] std::vector<descriptor::HostPropertySpec> decodeHostPropertySpecs(
    kolibri::binary::Reader& reader
  );
} // namespace expo::modules::v2::decoders
