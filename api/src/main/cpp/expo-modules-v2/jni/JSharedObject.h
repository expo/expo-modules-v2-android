#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/members/Method.h>

namespace expo::modules::v2 {
  struct JSharedObject : kolibri::JavaClass<JSharedObject> {
    static constexpr std::string_view descriptor =
      "io/github/expo/modules/v2/SharedObject";

    static int additionalMemoryPressureOf(JNIEnv* env, const jobject instance) {
      return getAdditionalMemoryPressure_(env, instance);
    }

  private:
    static constexpr Method<"getAdditionalMemoryPressure", jint()> getAdditionalMemoryPressure_{};
  };
} // namespace expo::modules::v2
