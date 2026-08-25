#include <expo-jsi/ByteArrayBuffer.h>

namespace expo::jsi {
  ByteArrayBuffer::ByteArrayBuffer(const uint8_t* data, size_t size)
    : bytes_(data, data + size) {
  }

  size_t ByteArrayBuffer::size() const {
    return bytes_.size();
  }

  uint8_t* ByteArrayBuffer::data() {
    return bytes_.data();
  }
}
