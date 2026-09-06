#pragma once

#include <vector>

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/descriptor/SharedClassSpec.h>

namespace expo::modules::v2::decoders {
  [[nodiscard]] descriptor::SharedClassSpec decodeSharedClassSpec(
    kolibri::binary::Reader& reader
  );

  [[nodiscard]] std::vector<descriptor::SharedClassSpec> decodeSharedClassSpecs(
    kolibri::binary::Reader& reader
  );
}
