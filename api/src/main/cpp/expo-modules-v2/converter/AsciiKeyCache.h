#pragma once

#include <string_view>

#include <jsi/jsi.h>

#include <expo-modules-v2/Config.h>
#include <expo-modules-v2/utils/StringLruCache.h>

namespace expo::modules::v2 {
  /**
   * The property names of recently decoded ASCII map keys, so a key that keeps coming back is not
   * turned into a `PropNameID` again. Least recently used names are evicted first.
   */
  class AsciiKeyCache {
  public:
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
    StringLruCache<facebook::jsi::PropNameID, config::kPropertyNameCacheSlots - 1> names_;
  };
}
