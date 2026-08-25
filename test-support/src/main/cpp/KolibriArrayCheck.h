#pragma once

#include <string>

namespace expo::modules::v2 {
  /**
   * Exercises the kolibri JNI array API (kolibri/array.h) against the live JVM: creation, region
   * copies, pins, object arrays, nested arrays, and method tokens with array signatures. Returns
   * "ok" or a description of the first failure. Test-only, driven by the
   * `ExpoTestSupport.__kolibriArrayCheck` host function.
   */
  std::string runKolibriArrayCheck();

  /**
   * Registers array-typed native methods on the Kotlin test fixture
   * `expo.modules.v2.testapp.KolibriArrayFixture` (test-app test sources), deriving every JNI
   * signature from the C++ parameter types. Throws if the class is not on the classpath.
   */
  void bindKolibriArrayFixture();
}
