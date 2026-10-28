#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/array.h>
#include <kolibri/class.h>
#include <kolibri/members/Field.h>

#include <expo-modules-v2/unions/UnionSchema.h>

namespace expo::modules::v2 {
  struct JUnionSchemaData : kolibri::JavaClass<JUnionSchemaData> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/unions/UnionSchemaData";

    struct Accessors : BaseAccessors {
      [[nodiscard]] std::string name(JNIEnv* env) const;

      [[nodiscard]] std::optional<std::string> discriminator(JNIEnv* env) const;

      [[nodiscard]] bool isBufferSafe(JNIEnv* env) const;

      [[nodiscard]] std::vector<UnionVariantSpec> variants(JNIEnv* env) const;
    };

  private:
    // clang-format off
    static constexpr Field<"name", kolibri::Ref<kolibri::JString>> name_{};
    static constexpr Field<"discriminator", kolibri::Ref<kolibri::JString>> discriminator_{};
    static constexpr Field<"bufferSafe", jboolean> bufferSafe_{};
    static constexpr Field<"variantKinds", kolibri::Ref<kolibri::JIntArray>> variantKinds_{};
    static constexpr Field<"variantTags", kolibri::Ref<kolibri::JStringArray>> variantTags_{};
    static constexpr Field<"variantTypes", kolibri::Ref<kolibri::JIntArray>> variantTypes_{};
    // clang-format on
  };
}
