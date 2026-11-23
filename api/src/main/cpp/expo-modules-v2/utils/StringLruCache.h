#pragma once

#include <array>
#include <bit>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>
#include <vector>

namespace expo::modules::v2 {
  /**
   * A cache from strings to [Value]s that holds at most [Capacity] of them and evicts the least
   * recently used one to make room.
   *
   * ```
   * StringLruCache<Name, 255> names;
   * const Name& name = names.getOrInsert(key, [&] { return makeName(key); });
   * ```
   *
   * Lookups allocate nothing: it is meant for hot paths that look up the same few keys over and
   * over. A value stays at its address until it is evicted or the cache is cleared.
   */
  template<class Value, size_t Capacity>
  class StringLruCache {
    static_assert(Capacity > 0);
    static_assert(
      std::is_nothrow_move_constructible_v<Value> && std::is_nothrow_move_assignable_v<Value>,
      "A value moves into its entry after the cache has started to change"
    );

  public:
    StringLruCache() {
      // Reserved once, so an entry - and the value `getOrInsert` returned - never moves.
      entries_.reserve(Capacity);
      table_.fill(kNone);
    }

    StringLruCache(const StringLruCache&) = delete;
    StringLruCache& operator=(const StringLruCache&) = delete;

    /**
     * The value cached under [key], or the one [make] returns, cached under [key] first. Either way
     * [key] becomes the most recently used. When [make] throws, the cache is left as it was.
     *
     * The reference is valid until the entry is evicted - at the earliest by the next call that
     * inserts - or the cache is cleared.
     */
    template<class Make>
    [[nodiscard]] Value& getOrInsert(const std::string_view key, Make&& make) {
      const uint64_t hash = std::hash<std::string_view>{}(key);
      if (const auto found = find(key, hash); found != kNone) {
        touch(found);
        return *entries_[found].value;
      }
      return insert(key, hash, std::forward<Make>(make)());
    }

    void clear() noexcept {
      entries_.clear();
      table_.fill(kNone);
      newest_ = kNone;
      oldest_ = kNone;
    }

  private:
    // An entry's position in `entries_`, which also links the recency order.
    using Index = uint16_t;
    static constexpr Index kNone = UINT16_MAX;
    static_assert(Capacity < kNone);

    // The hash index is open-addressed and at most half full, so probes stay short.
    static constexpr size_t kTableSize = std::bit_ceil(Capacity * 2);
    static constexpr size_t kMask = kTableSize - 1;

    struct Entry {
      std::string key;
      uint64_t hash = 0;
      std::optional<Value> value;
      // Neighbors in the recency order.
      Index newer = kNone;
      Index older = kNone;
    };

    [[nodiscard]] Index find(const std::string_view key, const uint64_t hash) const noexcept {
      for (size_t slot = hash & kMask;; slot = (slot + 1) & kMask) {
        const Index index = table_[slot];
        if (index == kNone) {
          return kNone;
        }
        const Entry& entry = entries_[index];
        if (entry.hash == hash && entry.key == key) {
          return index;
        }
      }
    }

    Value& insert(const std::string_view key, const uint64_t hash, Value value) {
      Index index;
      if (entries_.size() < Capacity) {
        std::string ownedKey(key); // May throw, before anything changes.
        index = static_cast<Index>(entries_.size());
        entries_.push_back(Entry{
          .key = std::move(ownedKey),
          .hash = hash,
          .value = std::move(value)
        });
      } else {
        // The least recently used entry takes the new key. Its string is grown while the entry is
        // still in place, so the only step that may throw comes before anything changes.
        index = oldest_;
        Entry& entry = entries_[index];
        entry.key.reserve(key.size());
        removeFromTable(index);
        unlink(index);
        entry.key.assign(key);
        entry.hash = hash;
        entry.value = std::move(value);
      }
      addToTable(index);
      linkAsNewest(index);
      return *entries_[index].value;
    }

    void touch(const Index index) noexcept {
      if (index != newest_) {
        unlink(index);
        linkAsNewest(index);
      }
    }

    // --- Hash index ------------------------------------------------------------------------------

    [[nodiscard]] size_t homeOf(const Index index) const noexcept {
      return entries_[index].hash & kMask;
    }

    void addToTable(const Index index) noexcept {
      size_t slot = homeOf(index);
      while (table_[slot] != kNone) {
        slot = (slot + 1) & kMask;
      }
      table_[slot] = index;
    }

    void removeFromTable(const Index index) noexcept {
      size_t hole = homeOf(index);
      while (table_[hole] != index) {
        hole = (hole + 1) & kMask;
      }
      table_[hole] = kNone;
      // Backward-shift deletion: later entries of the probe run move up into the hole, unless their
      // home slot lies cyclically after it, so every lookup still finds them without tombstones.
      for (size_t slot = (hole + 1) & kMask; table_[slot] != kNone; slot = (slot + 1) & kMask) {
        const size_t home = homeOf(table_[slot]);
        const bool homeAfterHole = ((slot - home) & kMask) < ((slot - hole) & kMask);
        if (!homeAfterHole) {
          table_[hole] = table_[slot];
          table_[slot] = kNone;
          hole = slot;
        }
      }
    }

    // --- Recency order ---------------------------------------------------------------------------

    void unlink(const Index index) noexcept {
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

    void linkAsNewest(const Index index) noexcept {
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

    std::vector<Entry> entries_;
    // Slot -> entry, or kNone.
    std::array<Index, kTableSize> table_;
    Index newest_ = kNone;
    Index oldest_ = kNone;
  };
}
