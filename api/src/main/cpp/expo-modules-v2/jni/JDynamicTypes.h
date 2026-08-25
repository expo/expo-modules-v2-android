#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/members/StaticMethod.h>

#include <expo-modules-v2/descriptor/CppType.h>

namespace expo::modules::v2 {
  struct JDynamicTypes : kolibri::JavaClass<JDynamicTypes> {
    static constexpr std::string_view descriptor = "expo/modules/v2/types/DynamicTypes";

    static CppType kindOf(JNIEnv* env, jobject obj);

  private:
    static constexpr StaticMethod<"kindOf", jint(jobject)> kindOf_{};
  };
}
