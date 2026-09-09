#pragma once

#include <vector>

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/descriptor/EventSpec.h>

namespace expo::modules::v2::decoders {
  [[nodiscard]] descriptor::EventSpec decodeEventSpec(kolibri::binary::Reader& reader);

  [[nodiscard]] std::vector<descriptor::EventSpec> decodeEventSpecs(
    kolibri::binary::Reader& reader
  );
} // namespace expo::modules::v2::decoders
