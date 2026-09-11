#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/members/StaticMethod.h>

#include <expo-modules-v2/jni/JAsyncContext.h>
#include <expo-modules-v2/objects/ObjectId.h>

namespace expo::modules::v2 {
  /** `io.github.expo.modules.v2.events.EventNatives`: where Kotlin's emit natives live. */
  struct JEventNatives : kolibri::JavaClass<JEventNatives> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/events/EventNatives";

    static void registerNatives(JNIEnv* env);
  };

  /** `io.github.expo.modules.v2.events.EventSupport`: the Kotlin side of the event emitter. */
  struct JEventSupport : kolibri::JavaClass<JEventSupport> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/events/EventSupport";

    static void observe(
      JNIEnv* env,
      jobject instance,
      int eventIndex,
      jobject context,
      bool observing
    );

  private:
    // clang-format off
    static constexpr StaticMethod<
      "observe",
      void(kolibri::Ref<objects::JExpoObject>, jint, kolibri::Ref<JAsyncContext>, jboolean)
    > observe_{};
    // clang-format on
  };
} // namespace expo::modules::v2
