#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/class.h>

#include <expo-modules-v2/decoders/ModuleDescriptorDecoder.h>

namespace expo::modules::v2 {
  struct JModuleRegistry : kolibri::JavaClass<JModuleRegistry> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/modules/ModuleRegistry";

    using ClassOf = std::function<jclass(JNIEnv* env, jobject instance)>;

    struct Accessors : BaseAccessors {
      /** The export table of [moduleName], or nothing if no such module is registered. */
      std::optional<descriptor::ModuleDescriptorPayload> encodeModule(
        JNIEnv* env,
        std::string moduleName,
        const ClassOf& classOf
      ) const;

      std::vector<std::string> encodeModuleNames(JNIEnv* env) const;
    };

  private:
    static constexpr Method<"encodeModule", kolibri::Ref<>(jstring)> encodeModule{};
    static constexpr Method<"encodeModuleNames", jint()> encodeModuleNames{};
  };
}
