#pragma once

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2::decoders {
  [[nodiscard]] ExpectedType decodeExpectedType(
    kolibri::binary::Reader& reader,
    bool allowBufferedHead = false
  );
}
