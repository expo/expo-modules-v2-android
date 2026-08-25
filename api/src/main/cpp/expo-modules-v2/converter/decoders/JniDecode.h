#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  facebook::jsi::Value decodeFromJni(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object,
    const ExpectedType& type
  );

  facebook::jsi::Value decodeFromJniDynamic(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object
  );
}
