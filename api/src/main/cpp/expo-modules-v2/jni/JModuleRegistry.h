#pragma once

#include <string>
#include <optional>
#include <string_view>
#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/class.h>

#include <expo-modules-v2/decoders/ModuleDescriptorDecoder.h>

namespace expo::modules::v2 {
  struct JModuleRegistry : kolibri::JavaClass<JModuleRegistry> {
    static constexpr std::string_view descriptor = "expo/modules/v2/modules/ModuleRegistry";

    struct Module {
      kolibri::Ref<> instance;
      descriptor::ModuleDescriptorPayload descriptor;
    };

    struct Accessors : BaseAccessors {
      std::optional<Module> encodeModule(JNIEnv* env, std::string moduleName) const;

      std::vector<std::string> encodeModuleNames(JNIEnv* env) const;
    };

  private:
    static constexpr Method<"encodeModule", kolibri::Ref<>(jstring)> encodeModule{};
    static constexpr Method<"encodeModuleNames", jint()> encodeModuleNames{};
  };
}
