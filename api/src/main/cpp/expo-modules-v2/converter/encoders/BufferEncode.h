#pragma once

#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <kolibri/binary/BinaryBuffer.h>

namespace expo::modules::v2 {
  void encodeToBuffer(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type,
    kolibri::binary::BinaryBuffer& out
  );

  void encodeToBufferDynamic(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    kolibri::binary::BinaryBuffer& out
  );
} // namespace expo::modules::v2
