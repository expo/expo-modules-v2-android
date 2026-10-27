#include <expo-modules-v2/converter/AsciiKeyCache.h>

namespace expo::modules::v2 {
  AsciiKeyCache::AsciiKeyCache() {
    keys_.reserve(config::kPropertyNameCacheSlots);
  }

  const facebook::jsi::PropNameID* AsciiKeyCache::get(
    facebook::jsi::Runtime& rt,
    const std::string_view key
  ) {
    if (const auto cached = keys_.find(key); cached != keys_.end()) {
      order_.splice(order_.begin(), order_, cached->second.position);
      return &cached->second.name;
    }

    if (key.size() > config::kMaxCachedPropertyNameLength) {
      return nullptr;
    }

    auto name = facebook::jsi::PropNameID::forAscii(rt, key.data(), key.size());

    if (keys_.size() < kCapacity) {
      const auto added = keys_.emplace(
        std::string(key),
        Entry{
          .name = std::move(name),
          .position = {}
        }
      ).first;

      try {
        order_.push_front(&added->first);
      } catch (...) {
        keys_.erase(added);
        throw;
      }

      added->second.position = order_.begin();
      return &added->second.name;
    }

    // The new key takes the list node of the least recently used one, which moves to the front.
    const auto last = std::prev(order_.end());
    const std::string* victim = *last;
    const auto added = keys_.emplace(
      std::string(key),
      Entry{
        .name = std::move(name),
        .position = last
      }
    ).first;
    keys_.erase(keys_.find(*victim));
    *last = &added->first;
    order_.splice(order_.begin(), order_, last);
    return &added->second.name;
  }

  void AsciiKeyCache::clear() noexcept {
    keys_.clear();
    order_.clear();
  }
}
