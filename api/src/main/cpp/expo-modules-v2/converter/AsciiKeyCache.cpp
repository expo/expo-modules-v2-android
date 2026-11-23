#include <expo-modules-v2/converter/AsciiKeyCache.h>

namespace expo::modules::v2 {
  const facebook::jsi::PropNameID* AsciiKeyCache::get(
    facebook::jsi::Runtime& rt,
    const std::string_view key
  ) {
    // A longer key is never cached, so it can skip the lookup too.
    if (key.size() > config::kMaxCachedPropertyNameLength) {
      return nullptr;
    }
    return &names_.getOrInsert(key, [&] {
      return facebook::jsi::PropNameID::forAscii(rt, key.data(), key.size());
    });
  }

  void AsciiKeyCache::clear() noexcept {
    names_.clear();
  }
}
