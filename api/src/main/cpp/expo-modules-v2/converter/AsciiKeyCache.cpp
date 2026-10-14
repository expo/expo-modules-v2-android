#include <expo-modules-v2/converter/AsciiKeyCache.h>

#include <cstring>
#include <utility>

namespace expo::modules::v2 {
  namespace {
    constexpr size_t kMask = 512 - 1;

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

  AsciiKeyCache::AsciiKeyCache() {
    static_assert(kTableSize - 1 == kMask && (kTableSize & kMask) == 0);
    static_assert(kCapacity * 2 <= kTableSize && kCapacity < kNone);
    // Reserved once, so an entry - and the name `get` returned - never moves.
    entries_.reserve(kCapacity);
    table_.fill(kNone);
  }

  uint64_t AsciiKeyCache::hashOf(const std::string_view key) noexcept {
    // Keys are short: eight bytes at a time, then the tail, each folded in with a multiply.
    uint64_t hash = 0x9e3779b97f4a7c15ULL ^ key.size();
    const char* bytes = key.data();
    size_t remaining = key.size();
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

  const facebook::jsi::PropNameID* AsciiKeyCache::get(
    facebook::jsi::Runtime& rt,
    const std::string_view key
  ) {
    const uint64_t hash = hashOf(key);
    for (size_t slot = hash & kMask;; slot = (slot + 1) & kMask) {
      const uint16_t index = table_[slot];
      if (index == kNone) {
        break;
      }
      Entry& entry = entries_[index];
      if (entry.hash == hash && entry.key == key) {
        if (index != newest_) {
          unlink(index);
          linkAsNewest(index);
        }
        return &*entry.name;
      }
    }

    if (key.size() > kMaxKeyLength) {
      return nullptr;
    }

    // Made before anything changes, so a throw leaves the cache as it was.
    auto name = facebook::jsi::PropNameID::forAscii(rt, key.data(), key.size());

    uint16_t index;
    if (entries_.size() < kCapacity) {
      index = static_cast<uint16_t>(entries_.size());
      entries_.emplace_back();
    } else {
      // The least recently used entry takes the new key.
      index = oldest_;
      removeFromTable(index);
      unlink(index);
    }

    Entry& entry = entries_[index];
    entry.key.assign(key);
    entry.hash = hash;
    entry.name = std::move(name);
    insertIntoTable(index);
    linkAsNewest(index);
    return &*entry.name;
  }

  void AsciiKeyCache::clear() noexcept {
    entries_.clear();
    table_.fill(kNone);
    newest_ = kNone;
    oldest_ = kNone;
  }

  size_t AsciiKeyCache::slotOf(const uint16_t index) const noexcept {
    size_t slot = entries_[index].hash & kMask;
    while (table_[slot] != index) {
      slot = (slot + 1) & kMask;
    }
    return slot;
  }

  void AsciiKeyCache::insertIntoTable(const uint16_t index) noexcept {
    size_t slot = entries_[index].hash & kMask;
    while (table_[slot] != kNone) {
      slot = (slot + 1) & kMask;
    }
    table_[slot] = index;
  }

  void AsciiKeyCache::removeFromTable(const uint16_t index) noexcept {
    // Backward-shift deletion: later entries of the probe run move up into the hole, unless their
    // home slot lies cyclically after it, so every lookup still finds them without tombstones.
    size_t hole = slotOf(index);
    table_[hole] = kNone;
    for (size_t slot = (hole + 1) & kMask; table_[slot] != kNone; slot = (slot + 1) & kMask) {
      const size_t home = entries_[table_[slot]].hash & kMask;
      const bool homeAfterHole = ((slot - home) & kMask) < ((slot - hole) & kMask);
      if (!homeAfterHole) {
        table_[hole] = table_[slot];
        table_[slot] = kNone;
        hole = slot;
      }
    }
  }

  void AsciiKeyCache::unlink(const uint16_t index) noexcept {
    Entry& entry = entries_[index];
    if (entry.newer != kNone) {
      entries_[entry.newer].older = entry.older;
    } else {
      newest_ = entry.older;
    }
    if (entry.older != kNone) {
      entries_[entry.older].newer = entry.newer;
    } else {
      oldest_ = entry.newer;
    }
    entry.newer = kNone;
    entry.older = kNone;
  }

  void AsciiKeyCache::linkAsNewest(const uint16_t index) noexcept {
    Entry& entry = entries_[index];
    entry.older = newest_;
    entry.newer = kNone;
    if (newest_ != kNone) {
      entries_[newest_].newer = index;
    } else {
      oldest_ = index;
    }
    newest_ = index;
  }
}
