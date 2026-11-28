#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>

namespace expo::modules::v2::views {
  /** `io.github.expo.modules.v2.views.ViewPropsNatives`: the props decoder's entry points. */
  struct ViewPropsNatives : kolibri::JavaClass<ViewPropsNatives> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/views/ViewPropsNatives";

    static void registerNatives(JNIEnv* env);
  };
} // namespace expo::modules::v2::views
