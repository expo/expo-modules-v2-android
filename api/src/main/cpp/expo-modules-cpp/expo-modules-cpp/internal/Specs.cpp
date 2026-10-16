#include <expo-modules-cpp/internal/Specs.h>

#include <algorithm>
#include <array>
#include <stdexcept>

namespace expo::modules::v2::cpp::detail {
  namespace {
    constexpr std::array kEmitterMembers = std::to_array<std::string_view>({
      "addListener",
      "removeListener",
      "removeAllListeners",
      "listenerCount",
      "emit",
    });

    /** What every shared object prototype defines itself, beyond the emitter's members. */
    constexpr std::array kClassMembers = std::to_array<std::string_view>({
      "release",
      "__expoSharedObjectId",
    });
  } // namespace

  bool isReservedExportName(const std::string_view name) noexcept {
    return std::ranges::find(kEmitterMembers, name) != kEmitterMembers.end();
  }

  void ExportNames::claim(const std::string& name) {
    const bool reservedByClass = isClass_ && std::ranges::find(kClassMembers, name) != kClassMembers.end();
    if (isReservedExportName(name) || reservedByClass) {
      throw std::invalid_argument(
        "Export '" + name + "' of '" + owner_ + "' is reserved: every module and shared object " +
        "carries the event emitter members addListener, removeListener, removeAllListeners, " +
        "listenerCount and emit" + (isClass_ ? ", and every shared object release()" : "")
      );
    }
    if (std::ranges::find(names_, name) != names_.end()) {
      throw std::invalid_argument(
        "Export '" + name + "' is already declared in '" + owner_ + "'"
      );
    }
    names_.push_back(name);
  }
} // namespace expo::modules::v2::cpp::detail
