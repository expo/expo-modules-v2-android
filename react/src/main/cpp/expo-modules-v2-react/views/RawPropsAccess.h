#pragma once

#include <optional>

#include <jsi/jsi.h>
#include <react/renderer/core/RawProps.h>

namespace expo::modules::v2::react::views {
  /**
   * The props object behind a `RawProps` in JSI mode and the runtime it belongs to. Valid only during
   * the `cloneProps` call that got the `RawProps`, on that call's thread.
   */
  struct JSIRawProps {
    facebook::jsi::Runtime& runtime;
    const facebook::jsi::Value& value;
  };

  /**
   * The props object of [rawProps] when it is in JSI mode, or nullopt when it is empty or built from
   * `folly::dynamic` (Reanimated, `setNativeProps`, animations).
   *
   * `RawProps` keeps the object private. Its public `forEachItem` hands out one `RawValue` at a time,
   * which can't be turned back into the `jsi::Value` the converters read. Same technique as
   * expo-modules-core's iOS v2 views (`ios/Fabric/v2/RawPropsAccess.cpp`): it fails to compile, rather
   * than misbehave, when React Native renames or retypes the members.
   */
  std::optional<JSIRawProps> jsiRawProps(const facebook::react::RawProps& rawProps);
} // namespace expo::modules::v2::react::views
