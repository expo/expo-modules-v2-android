#pragma once

#include <cstddef>
#include <cstdint>

#include <jsi/jsi.h>

namespace expo::modules::v2 {
  /** Bytes JavaScript owns, valid while the value they came from is. */
  struct ByteView {
    const uint8_t* data;
    size_t size;
  };

  ByteView byteViewOf(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value);
}
