#pragma once

#include <optional>
#include <string>
#include <string_view>

#include <jni.h>

#include <kolibri/JavaClass.h>
#include <kolibri/Ref.h>
#include <kolibri/class.h>
#include <kolibri/members/StaticMethod.h>

#include <expo-modules-v2/descriptor/ModuleDescriptorPayload.h>
#include <expo-modules-v2/jni/JSharedObject.h>

namespace expo::modules::v2 {
  struct JSharedObjectRegistry : kolibri::JavaClass<JSharedObjectRegistry> {
    static constexpr std::string_view descriptor =
      "io/github/expo/modules/v2/sharedobjects/SharedObjectRegistry";

    struct ClassExports {
      std::string jsName;
      descriptor::ModuleDescriptorPayload descriptor;
    };

    /** The shared class id of [instance]; the per-instance id is `objects::ObjectId`. */
    static int classIdOf(JNIEnv* env, jobject instance);

    static void release(JNIEnv* env, jobject instance);

    static std::optional<ClassExports> encodeClass(JNIEnv* env, int classId, jclass declaringClass);

    static kolibri::Ref<kolibri::JClass> sharedClassOf(JNIEnv* env, int classId);

    static std::optional<std::string> classDescriptorOf(JNIEnv* env, int classId);

  private:
    // clang-format off
    static constexpr StaticMethod<"classIdOf", jint(kolibri::Ref<JSharedObject>)> classIdOf_{};
    static constexpr StaticMethod<"release", void(kolibri::Ref<JSharedObject>)> release_{};
    static constexpr StaticMethod<"encodeClass", kolibri::Ref<kolibri::JString>(jint)> encodeClass_{};
    static constexpr StaticMethod<"sharedClassOf", kolibri::Ref<kolibri::JClass>(jint)> sharedClassOf_{};
    static constexpr StaticMethod<"classDescriptorOf", kolibri::Ref<kolibri::JString>(jint)> classDescriptorOf_{};
    // clang-format on
  };
} // namespace expo::modules::v2
