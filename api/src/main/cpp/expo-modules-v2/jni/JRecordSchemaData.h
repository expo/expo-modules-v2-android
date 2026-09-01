#pragma once

#include <string>
#include <string_view>
#include <vector>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/array.h>
#include <kolibri/class.h>
#include <kolibri/members/Field.h>

#include <expo-modules-v2/records/RecordSchema.h>

namespace expo::modules::v2 {
  struct JRecordSchemaData : kolibri::JavaClass<JRecordSchemaData> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/records/RecordSchemaData";

    struct Accessors : BaseAccessors {
      [[nodiscard]] std::string name(JNIEnv* env) const;

      [[nodiscard]] std::string jniDescriptor(JNIEnv* env) const;

      [[nodiscard]] bool isBufferSafe(JNIEnv* env) const;

      [[nodiscard]] std::vector<RecordFieldSpec> fields(JNIEnv* env) const;
    };

  private:
    // clang-format off
    static constexpr Field<"name", kolibri::Ref<kolibri::JString>> name_{};
    static constexpr Field<"jniDescriptor", kolibri::Ref<kolibri::JString>> jniDescriptor_{};
    static constexpr Field<"bufferSafe", jboolean> bufferSafe_{};
    static constexpr Field<"fieldNames", kolibri::Ref<kolibri::JStringArray>> fieldNames_{};
    static constexpr Field<"fieldTypes", kolibri::Ref<kolibri::JIntArray>> fieldTypes_{};
    static constexpr Field<"fieldOptional", kolibri::Ref<kolibri::JBooleanArray>> fieldOptional_{};
    // clang-format on
  };
}
