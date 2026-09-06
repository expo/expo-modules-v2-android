#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/defines.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <expo-modules-v2/descriptor/FunctionSpec.h>

namespace expo::modules::v2 {
  struct JniMethodCall {
    facebook::jsi::Runtime& rt;
    JNIEnv* env;
    jobject receiver;
    jclass declaringClass;
    jmethodID method;
    const ExpectedType& returnType;
    const jvalue* args;
  };

  using JniMethodInvoker = facebook::jsi::Value (*)(const JniMethodCall& call);

  using FunctionInvoker = facebook::jsi::Value (*)(
    facebook::jsi::Runtime& rt,
    JNIEnv* env,
    const descriptor::FunctionSpec& spec,
    jobject receiver,
    const facebook::jsi::Value* args,
    size_t count
  );

  HIDDEN FunctionInvoker selectStaticObjectInvoker(bool hasBufferedArgs, bool needsLocalFrame);

  HIDDEN FunctionInvoker selectFunctionInvoker(
    const ExpectedType& returnType,
    bool hasBufferedArgs,
    bool needsLocalFrame,
    bool async
  );
} // namespace expo::modules::v2
