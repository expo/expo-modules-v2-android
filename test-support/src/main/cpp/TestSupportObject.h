#pragma once

#include <jsi/jsi.h>

#include <string>

namespace expo::modules::v2 {
  /**
   * Installs `globalThis.ExpoTestSupport` — the test/benchmark host-function suite
   * (`__convertRoundTrip`, `__binaryNativeRoundTrip`, `__nativeRoundTrip`,
   * `__nativeStateChainSmoke`, `__kolibriArrayCheck`, `__kolibriArrayBindFixture` and, when
   * compiled in, `__follyBench`) — into `rt`. Driven by the
   * Kotlin `io.github.expo.modules.v2.testsupport.TestSupport.install` entry point (OnLoad.cpp); production
   * runtimes never carry this object.
   */
  void installTestSupport(facebook::jsi::Runtime& rt);

  /**
   * Moves a host object from one runtime to another the way `react-native-worklets` does, so the
   * mechanism shared objects rely on can be exercised without an app.
   *
   * Worklets clones a host object by holding the same `std::shared_ptr<jsi::HostObject>` in a
   * `SerializableHostObject` and calling `Object::createFromHostObject` in the target runtime
   * (`Common/cpp/worklets/SharedItems/Serializable.{h,cpp}`). These four lines are that, with the
   * serializable step left out.
   *
   * Evaluates [expression] in [from], requires the result to be a host object, and installs it in
   * [to] as `globalThis.<globalName>`.
   */
  void transplantHostObject(
    facebook::jsi::Runtime& from,
    facebook::jsi::Runtime& to,
    const std::string& expression,
    const std::string& globalName
  );
} // namespace expo::modules::v2
