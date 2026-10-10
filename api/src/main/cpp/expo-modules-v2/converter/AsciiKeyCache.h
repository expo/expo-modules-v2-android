#pragma once

#include <functional>
#include <list>
#include <string>
#include <string_view>
#include <unordered_map>

#include <jsi/jsi.h>

namespace expo::modules::v2 {
  class AsciiKeyCache {
  public:
    AsciiKeyCache();

    [[nodiscard]] const facebook::jsi::PropNameID* get(
      facebook::jsi::Runtime& rt,
      std::string_view key
    );

    void clear() noexcept;

  private:
    static constexpr size_t kSlots = 256;
    static constexpr size_t kCapacity = kSlots - 1;
    static constexpr size_t kMaxKeyLength = 64;

    struct KeyHash {
      using is_transparent = void;

      size_t operator()(const std::string_view key) const noexcept {
        return std::hash<std::string_view>{}(key);
      }
    };

    // The keys from the most to the least recently used. Elements of an unordered_map keep their
    // address when it rehashes, so the list holds the keys of the map itself.
    using Order = std::list<const std::string*>;

    struct Entry {
      facebook::jsi::PropNameID name;
      Order::iterator position;
    };

    std::unordered_map<std::string, Entry, KeyHash, std::equal_to<>> keys_;
    Order order_;
  };
}
