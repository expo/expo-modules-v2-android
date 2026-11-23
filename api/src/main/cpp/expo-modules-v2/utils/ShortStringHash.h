#pragma once

#include <cstdint>
#include <string_view>

namespace expo::modules::v2 {
  /**
   * A 64-bit hash for short strings such as property names: eight bytes at a time, then the tail,
   * each folded in with a multiply. Fast for short inputs, not meant to resist crafted collisions.
   */
  [[nodiscard]] uint64_t hashShortString(std::string_view value) noexcept;
}
