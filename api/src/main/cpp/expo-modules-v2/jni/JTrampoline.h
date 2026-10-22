#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/array.h>
#include <kolibri/members/StaticMethod.h>

namespace expo::modules::v2 {
  struct JTrampoline : kolibri::JavaClass<JTrampoline> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/args/Trampoline";

    static constexpr jint kOverflowArgumentsSentinel = -1;

    static kolibri::Ref<> takeOverflowResult(JNIEnv* env);

    static kolibri::Ref<kolibri::JObjectArray> prepareOverflowArguments(JNIEnv* env);

    /**
     * Has Kotlin write [value], a dynamic value in a JNI slot, onto the shared buffer: the payload
     * length, or [kOverflowArgumentsSentinel] when only a walk of the slot can read it.
     */
    static jint writeSlotDynamic(JNIEnv* env, jobject value);

  private:
    static constexpr StaticMethod<"takeOverflowResult", kolibri::Ref<>()> takeOverflowResult_{};
    static constexpr StaticMethod<
      "prepareOverflowArguments",
      kolibri::Ref<kolibri::JObjectArray>()
    > prepareOverflowArguments_{};
    static constexpr StaticMethod<"writeSlotDynamic", jint(jobject)> writeSlotDynamic_{};
  };
}
