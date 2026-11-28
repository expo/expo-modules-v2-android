#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>

namespace expo::modules::v2::react::views {
  /** `io.github.expo.modules.v2.react.views.ExpoViewRegistry`: registers view types with Fabric. */
  struct ExpoViewRegistry : kolibri::JavaClass<ExpoViewRegistry> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/react/views/ExpoViewRegistry";

    static void registerNatives(JNIEnv* env);
  };
} // namespace expo::modules::v2::react::views
