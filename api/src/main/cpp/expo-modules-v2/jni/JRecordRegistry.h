#pragma once

#include <memory>
#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/class.h>
#include <kolibri/members/StaticMethod.h>

#include <expo-modules-v2/jni/JRecordSchemaData.h>
#include <expo-modules-v2/records/RecordSchema.h>

namespace expo::modules::v2 {
  struct JRecordRegistry : kolibri::JavaClass<JRecordRegistry> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/records/RecordRegistry";

    static constexpr StaticMethod<"dynamicRecordToMap", kolibri::Ref<kolibri::JMap>(jobject)> dynamicRecordToMap{};

    static std::unique_ptr<RecordSchema> fetchSchema(JNIEnv* env, jint schemaId) {
      const auto data = fetchSchema_(env, schemaId);

      auto schema = std::make_unique<RecordSchema>();
      schema->id = schemaId;
      schema->name = data->name(env);
      schema->jniDescriptor = data->jniDescriptor(env);
      schema->bufferSafe = data->isBufferSafe(env);
      schema->fields = data->fields(env);
      return schema;
    }

  private:
    static constexpr StaticMethod<"fetchSchema", kolibri::Ref<JRecordSchemaData>(jint)> fetchSchema_{};
  };
}
