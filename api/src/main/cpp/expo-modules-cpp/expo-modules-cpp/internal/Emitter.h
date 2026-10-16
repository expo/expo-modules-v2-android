#pragma once

#include <expo-modules-cpp/Defines.h>

namespace expo::modules::v2::cpp::detail {
  class RuntimeData;

  /**
   * Installs `addListener`, `removeListener`, `removeAllListeners`, `listenerCount` and `emit` on
   * [target], the one prototype every module object and shared object class of a runtime
   * inherits from. They are not enumerable, so they never show up as exports.
   */
  void installEmitterMembers(RuntimeData& data, const js::Object& target);
} // namespace expo::modules::v2::cpp::detail
