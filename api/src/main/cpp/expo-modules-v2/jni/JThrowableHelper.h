#pragma once

#include <optional>
#include <string>
#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/array.h>
#include <kolibri/members/StaticMethod.h>

namespace expo::modules::v2 {
  /** What JavaScript learns about a throwable: the fields of the error it becomes. */
  struct ThrowableDetails {
    std::string code;
    std::string message;
    std::string stack;
  };

  struct JThrowable : kolibri::JavaClass<JThrowable> {
    static constexpr std::string_view descriptor = "java/lang/Throwable";
  };

  struct JThrowableHelper : kolibri::JavaClass<JThrowableHelper> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/errors/ThrowableHelper";

    /**
     * Takes the pending Java exception and describes it, or returns nothing when none is pending.
     * The exception is cleared either way, so the caller decides what JavaScript sees.
     */
    static std::optional<ThrowableDetails> takePending(JNIEnv* env);

  private:
    static constexpr StaticMethod<
      "describe",
      kolibri::Ref<kolibri::JStringArray>(kolibri::Ref<JThrowable>)
    > describe_{};
  };
}
