#pragma once

#include <jni.h>

#include <vector>

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/descriptor/HostPropertySpec.h>

namespace expo::modules::v2::decoders {
  [[nodiscard]] descriptor::HostPropertySpec decodeHostPropertySpec(
    kolibri::binary::Reader& reader,
    jclass declaringClass
  );

  [[nodiscard]] std::vector<descriptor::HostPropertySpec> decodeHostPropertySpecs(
    kolibri::binary::Reader& reader,
    jclass declaringClass
  );
} // namespace expo::modules::v2::decoders
