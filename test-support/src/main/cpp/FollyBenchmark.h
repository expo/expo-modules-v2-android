#pragma once

#include <jsi/jsi.h>

namespace expo::modules::v2 {
  /**
   * Installs `ExpoTestSupport.__follyBench(payload, iters)` — a micro-benchmark comparing
   * React Native's `folly::dynamic` value representation against the binary buffer codec on
   * the same `facebook::jsi::Value`. Only available when the build was configured with
   * `-DEXPO_FOLLY_BENCHMARK=ON` (requires a local folly, e.g. `brew install folly`); otherwise
   * this is a no-op and the property is absent.
   */
  void installFollyBench(facebook::jsi::Runtime& rt, facebook::jsi::Object& core);
} // namespace expo::modules::v2
