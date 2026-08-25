#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  jvalue encodeToJniValue(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type
  );

  jobject encodeToJni(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type
  );

  jobject encodeToJniDynamic(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value
  );
}
