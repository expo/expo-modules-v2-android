#pragma once

#include <jni.h>

#include <expo-modules-v2/descriptor/FunctionSpec.h>

namespace expo::modules::v2::descriptor {
  struct StaticFunctionSpec : FunctionSpec {
    void resolveFunction(JNIEnv* env, jclass clazz);
  };
}
