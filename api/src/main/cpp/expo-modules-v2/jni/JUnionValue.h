#pragma once

#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/members/Constructor.h>
#include <kolibri/members/Field.h>

namespace expo::modules::v2 {
  /** Kotlin's `UnionValue`: the variant a union value is, and the bridge value it carries. */
  struct JUnionValue : kolibri::JavaClass<JUnionValue> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/unions/UnionValue";

    static jobject create(JNIEnv* env, const jint index, const jobject payload) {
      return constructor_.createRaw(env, index, payload);
    }

    static jint indexOf(JNIEnv* env, const jobject value) {
      return index_(env, value);
    }

    static kolibri::Ref<> payloadOf(JNIEnv* env, const jobject value) {
      return payload_(env, value);
    }

  private:
    static constexpr Constructor<void(jint, jobject)> constructor_{};
    static constexpr Field<"index", jint> index_{};
    static constexpr Field<"payload", kolibri::Ref<>> payload_{};
  };
} // namespace expo::modules::v2
