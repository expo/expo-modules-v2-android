#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <string>
#include <unordered_map>
#include <vector>

#include <kolibri/Ref.h>

#include <expo-modules-v2/jni/JModuleRegistry.h>

namespace expo::modules::v2::jsi {
  class ModulesHostObject : public facebook::jsi::HostObject {
  public:
    ModulesHostObject(
      JNIEnv* env,
      jobject registry
    );

    ~ModulesHostObject() override;

    facebook::jsi::Value get(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::PropNameID& name
    ) override;

    void set(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::PropNameID& name,
      const facebook::jsi::Value& value
    ) override;

    std::vector<facebook::jsi::PropNameID> getPropertyNames(
      facebook::jsi::Runtime& rt
    ) override;

  private:
    kolibri::GlobalRef<JModuleRegistry> registry_;
    std::unordered_map<std::string, facebook::jsi::Object> materialized_;
  };
} // namespace expo::modules::v2::jsi
