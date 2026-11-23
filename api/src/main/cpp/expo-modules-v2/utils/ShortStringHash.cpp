#include <expo-modules-v2/utils/ShortStringHash.h>

#include <cstring>

namespace expo::modules::v2 {
  namespace {
    [[nodiscard]] uint64_t mix(uint64_t value) noexcept {
      value ^= value >> 32;
      value *= 0xd6e8feb86659fd93ULL;
      value ^= value >> 32;
      return value;
    }

    /**
     * The 1 to 7 bytes at [bytes] as a little-endian word with zeros above them, as a `memcpy` into
     * a zeroed word gives, but from fixed-size loads: a `memcpy` of a run-time length is a call.
     * Overlapping loads set the bytes they share twice, to the same value.
     */
    [[nodiscard]] uint64_t loadTail(const char* bytes, const size_t count) noexcept {
      if (count >= sizeof(uint32_t)) {
        uint32_t low;
        uint32_t high;
        std::memcpy(&low, bytes, sizeof(low));
        std::memcpy(&high, bytes + count - sizeof(high), sizeof(high));
        return low | (static_cast<uint64_t>(high) << ((count - sizeof(high)) * 8));
      }
      const auto byteAt = [bytes](const size_t i) {
        return static_cast<uint64_t>(static_cast<uint8_t>(bytes[i])) << (i * 8);
      };
      return byteAt(0) | byteAt(count / 2) | byteAt(count - 1);
    }
  } // namespace

  uint64_t hashShortString(const std::string_view value) noexcept {
    uint64_t hash = 0x9e3779b97f4a7c15ULL ^ value.size();
    const char* bytes = value.data();
    size_t remaining = value.size();
    while (remaining >= sizeof(uint64_t)) {
      uint64_t word;
      std::memcpy(&word, bytes, sizeof(word));
      hash = mix(hash ^ word);
      bytes += sizeof(word);
      remaining -= sizeof(word);
    }
    if (remaining > 0) {
      hash = mix(hash ^ loadTail(bytes, remaining));
    }
    return hash;
  }
}
