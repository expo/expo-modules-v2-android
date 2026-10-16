#pragma once

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/internal/Specs.h>

namespace expo::modules::v2::cpp::detail {
  class RuntimeData;

  /**
   * Defines on [prototype] what JavaScript sees of a shared object class: `release`, the functions,
   * the properties and the constants of [spec]. The emitter members are inherited, not defined here.
   */
  void installPrototypeMembers(RuntimeData& data, const js::Object& prototype, const ClassSpec& spec);

  /** The constructor JavaScript calls with `new` for [binding]'s class, sharing the class's prototype. */
  js::Function createClassConstructor(RuntimeData& data, const ClassBinding& binding);
} // namespace expo::modules::v2::cpp::detail
