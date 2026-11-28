#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>

namespace expo::modules::v2::views::baseline {
  /**
   * BENCHMARK ONLY - drop before this lands. Natives of `StateViewPropsBaseline`: the state-based props
   * transport of the first prototype, so the benchmark can compare it with the binary one.
   */
  struct StateViewPropsBaselineNatives : kolibri::JavaClass<StateViewPropsBaselineNatives> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/views/baseline/StateViewPropsBaseline";

    static void registerNatives(JNIEnv* env);
  };
} // namespace expo::modules::v2::views::baseline
