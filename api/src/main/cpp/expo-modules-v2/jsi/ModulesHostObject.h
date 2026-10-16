#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include <kolibri/Ref.h>

#include <expo-modules-v2/jni/JModuleRegistry.h>

namespace expo::modules::v2::jsi {
  class ModulesHostObject : public facebook::jsi::HostObject {
  public:
    /**
     * [cppModules] serves the modules written with the C++ API. It is asked for a name only after the
     * registry and the host have none, so a Kotlin module wins a name both define.
     */
    ModulesHostObject(
      JNIEnv* env,
      jobject registry,
      std::shared_ptr<facebook::jsi::HostObject> cppModules = nullptr,
      std::optional<facebook::jsi::Object> hostModules = std::nullopt
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
    std::shared_ptr<facebook::jsi::HostObject> cppModules_;
    std::optional<facebook::jsi::Object> hostModules_;
    std::unordered_map<std::string, facebook::jsi::Object> materialized_;
  };
} // namespace expo::modules::v2::jsi
