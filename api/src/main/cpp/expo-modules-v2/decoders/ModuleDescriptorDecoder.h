#pragma once

#include <jni.h>

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/descriptor/ModuleDescriptorPayload.h>

namespace expo::modules::v2::decoders {
  [[nodiscard]] descriptor::ModuleDescriptorPayload decodeModuleDescriptorPayload(
    kolibri::binary::Reader& reader,
    jclass declaringClass
  );
} // namespace expo::modules::v2::decoders
