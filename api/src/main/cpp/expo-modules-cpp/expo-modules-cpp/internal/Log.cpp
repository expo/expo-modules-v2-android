#include <expo-modules-cpp/internal/Log.h>

#include <cstdio>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace expo::modules::v2::cpp::detail {
  void logError(const std::string& message) {
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_ERROR, "ExpoModulesCpp", "%s", message.c_str());
#else
    std::fprintf(stderr, "[ExpoModulesCpp] %s\n", message.c_str());
#endif
  }
} // namespace expo::modules::v2::cpp::detail
