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

  /**
   * [decodeFromJni] that walks the slot with JNI calls throughout, without offering the value or
   * its parts to the shared buffer first.
   */
  facebook::jsi::Value decodeFromJniSlot(
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
