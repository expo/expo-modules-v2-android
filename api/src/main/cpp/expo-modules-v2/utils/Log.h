#pragma once

#include <iostream>
#include <string>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace expo::modules::v2 {
  /**
   * The bridge's one log sink. stdout is the process's console on a desktop JVM and nowhere at all
   * on Android, where logcat is the only place a line can land.
   */
  inline void logLine(const std::string& message) {
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "ExpoModulesCore", "%s", message.c_str());
#else
    std::cout << "[ExpoModulesCore] " << message << std::endl;
#endif
  }
} // namespace expo::modules::v2
