#pragma once

#include <memory>
#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/members/StaticMethod.h>

#include <expo-modules-v2/jni/JUnionSchemaData.h>
#include <expo-modules-v2/unions/UnionSchema.h>

namespace expo::modules::v2 {
  struct JUnionRegistry : kolibri::JavaClass<JUnionRegistry> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/unions/UnionRegistry";

    static std::unique_ptr<UnionSchema> fetchSchema(JNIEnv* env, jint unionId) {
      const auto data = fetchSchema_(env, unionId);

      auto schema = std::make_unique<UnionSchema>();
      schema->id = unionId;
      schema->name = data->name(env);
      schema->discriminator = data->discriminator(env);
      schema->bufferSafe = data->isBufferSafe(env);
      schema->variants = data->variants(env);

      for (size_t i = 0; i < schema->variants.size(); i++) {
        UnionVariantKind kind = schema->variants[i].kind;
        if (kind == UnionVariantKind::LITERAL) {
          kind = UnionVariantKind::STRING;
        }
        schema->byKind[static_cast<size_t>(kind)].push_back(static_cast<int>(i));
      }
      return schema;
    }

  private:
    static constexpr StaticMethod<"fetchSchema", kolibri::Ref<JUnionSchemaData>(jint)> fetchSchema_{};
  };
}
