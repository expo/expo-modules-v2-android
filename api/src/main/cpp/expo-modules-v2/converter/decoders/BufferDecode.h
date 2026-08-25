#pragma once

#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <kolibri/binary/BinaryReader.h>

namespace expo::modules::v2 {
  facebook::jsi::Value decodeFromBuffer(
    facebook::jsi::Runtime& rt,
    kolibri::binary::Reader& in,
    const ExpectedType& type
  );

  facebook::jsi::Value decodeFromBufferDynamic(
    facebook::jsi::Runtime& rt,
    kolibri::binary::Reader& in
  );
} // namespace expo::modules::v2
