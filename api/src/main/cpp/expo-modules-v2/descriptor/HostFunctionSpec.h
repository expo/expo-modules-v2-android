#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <expo-modules-v2/JniMethodInvoker.h>
#include <expo-modules-v2/descriptor/FunctionSpec.h>

namespace expo::modules::v2::descriptor {
  struct HostFunctionSpec : FunctionSpec {
    explicit HostFunctionSpec(FunctionSpec spec);

    facebook::jsi::Value invoke(
      facebook::jsi::Runtime& rt,
      jobject receiver,
      const facebook::jsi::Value* args,
      size_t count
    ) const;

  private:
    void resolve(JNIEnv* env) const;

    FunctionInvoker invoker_;
  };
}
