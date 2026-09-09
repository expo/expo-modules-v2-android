#pragma once

#include <jni.h>

#include <cstdint>
#include <string_view>

#include <kolibri/JavaClass.h>

namespace expo::modules::v2::objects {
  /** `io.github.expo.modules.v2.ExpoObject`: the Kotlin base every module and shared object extends. */
  struct JExpoObject : kolibri::JavaClass<JExpoObject> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/ExpoObject";

    static constexpr Field<"objectId", jlong> objectId_{};
  };

  /**
   * The process-wide id of a Kotlin instance JavaScript can hold. Kotlin assigns it in the
   * `ExpoObject` constructor and never changes it, so reading it is one JNI field read - no Java
   * call, no synchronization. A 64-bit counter: the number of instances is practically unlimited.
   */
  struct ObjectId {
    using Value = uint64_t;

    static Value of(JNIEnv* env, jobject instance) {
      return static_cast<Value>(JExpoObject::objectId_(env, instance));
    }
  };
} // namespace expo::modules::v2::objects
