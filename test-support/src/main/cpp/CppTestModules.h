#pragma once

#include <expo-modules-cpp/Context.h>

namespace expo::modules::v2 {
  /** Adds the C++ test modules (CppCalc, ShadowedByKotlin, CppLifecycle) to [context]. */
  void installCppTestModules(::expo::modules::v2::cpp::Context& context);

  /** Registers, and takes off again, a module served by every context in the process. */
  void registerStaticProbe();

  void unregisterStaticProbe();
} // namespace expo::modules::v2
