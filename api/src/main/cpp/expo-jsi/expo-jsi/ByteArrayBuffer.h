#pragma once

#include <jsi/jsi.h>

#include <cstdint>
#include <vector>

namespace expo::jsi {
  class ByteArrayBuffer : public facebook::jsi::MutableBuffer {
  public:
    ByteArrayBuffer(const uint8_t* data, size_t size);

    size_t size() const override;

    uint8_t* data() override;

  private:
    std::vector<uint8_t> bytes_;
  };
}
