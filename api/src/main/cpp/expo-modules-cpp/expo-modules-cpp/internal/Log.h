#pragma once

#include <string>

#include <expo-modules-cpp/Defines.h>

namespace expo::modules::v2::cpp::detail {
  /**
   * The library's one log sink: logcat on Android, where stderr goes nowhere, and stderr
   * everywhere else.
   */
  EXPO_API void logError(const std::string& message);
} // namespace expo::modules::v2::cpp::detail
