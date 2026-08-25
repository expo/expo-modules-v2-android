#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/members/Method.h>

namespace expo::modules::v2 {
  struct JPromise : kolibri::JavaClass<JPromise> {
    static constexpr std::string_view descriptor = "expo/modules/v2/async/Promise";
  };

  struct JAsyncContext : kolibri::JavaClass<JAsyncContext> {
    static constexpr std::string_view descriptor = "expo/modules/v2/async/AsyncContext";

    static void registerNatives(JNIEnv* env);

    struct Accessors : BaseAccessors {
      [[nodiscard]] kolibri::Ref<JPromise> createPromise(JNIEnv* env, jlong id) const;

      void invalidate(JNIEnv* env) const;

      void drainInlineSettles(JNIEnv* env) const;
    };

  private:
    static constexpr Method<"createPromise", kolibri::Ref<JPromise>(jlong)> createPromise_{};
    static constexpr Method<"invalidate", void()> invalidate_{};
    static constexpr Method<"drainInlineSettles", void()> drainInlineSettles_{};
  };
}
