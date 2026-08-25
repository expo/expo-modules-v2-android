#pragma once

#include <jsi/jsi.h>

namespace expo::modules::v2 {
  /**
   * Installs `globalThis.ExpoTestSupport` — the test/benchmark host-function suite
   * (`__convertRoundTrip`, `__binaryNativeRoundTrip`, `__nativeRoundTrip`,
   * `__nativeStateChainSmoke`, `__kolibriArrayCheck`, `__kolibriArrayBindFixture` and, when
   * compiled in, `__follyBench`) — into `rt`. Driven by the
   * Kotlin `expo.modules.v2.testsupport.TestSupport.install` entry point (OnLoad.cpp); production
   * runtimes never carry this object.
   */
  void installTestSupport(facebook::jsi::Runtime& rt);
} // namespace expo::modules::v2
