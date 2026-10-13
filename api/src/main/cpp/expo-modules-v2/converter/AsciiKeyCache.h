#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include <jsi/jsi.h>

namespace expo::modules::v2 {
  /**
   * The property names of recently decoded ASCII map keys, so a key that keeps coming back is not
   * turned into a `PropNameID` again. Least recently used names are evicted past [kCapacity].
   *
   * Every decoded map key looks itself up here, so the table is flat: an open-addressing index of
   * power-of-two size over one preallocated array of entries, which also link the LRU order by
   * index. Nothing is allocated per lookup, and an entry - with the name [get] hands out - stays
   * where it is until it is evicted.
   */
  class AsciiKeyCache {
  public:
    AsciiKeyCache();

    /**
     * The name for [key], or null when [key] is longer than the cache takes. The pointer is valid
     * until the next call.
     */
    [[nodiscard]] const facebook::jsi::PropNameID* get(
      facebook::jsi::Runtime& rt,
      std::string_view key
    );

    void clear() noexcept;

  private:
    static constexpr size_t kCapacity = 255;
    static constexpr size_t kMaxKeyLength = 64;
    // At most half full, so probes stay short.
    static constexpr size_t kTableSize = 512;
    static constexpr uint16_t kNone = UINT16_MAX;

    struct Entry {
      std::string key;
      uint64_t hash = 0;
      std::optional<facebook::jsi::PropNameID> name;
      uint16_t newer = kNone;
      uint16_t older = kNone;
    };

    [[nodiscard]] static uint64_t hashOf(std::string_view key) noexcept;

    [[nodiscard]] size_t slotOf(uint16_t index) const noexcept;

    void insertIntoTable(uint16_t index) noexcept;

    void removeFromTable(uint16_t index) noexcept;

    void unlink(uint16_t index) noexcept;

    void linkAsNewest(uint16_t index) noexcept;

    std::vector<Entry> entries_;
    std::array<uint16_t, kTableSize> table_;
    uint16_t newest_ = kNone;
    uint16_t oldest_ = kNone;
  };
}
