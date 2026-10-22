#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <memory>

namespace expo::modules::v2::sharedobjects {
  class SharedObjectState;

  class SharedObjects {
  public:
    static facebook::jsi::Value facadeFor(
      JNIEnv* env,
      facebook::jsi::Runtime& rt,
      jobject instance
    );

    /**
     * The state behind [object], borrowed: valid while [object] is held. Null when it is not a
     * shared object. Returned without ownership on purpose - this runs on every call of every
     * shared-object member, and a `shared_ptr` copy is two atomic operations each time.
     */
    static SharedObjectState* stateOf(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object
    );

    /**
     * [stateOf] for a member's receiver, skipping the check that [object] has a native state at all.
     * Members live on the prototype of shared objects, so `this` is one of them; null still means
     * "not this kind of object", but a plain object with no native state is not handled (debug
     * builds assert). Arguments come from script and keep going through [stateOf].
     */
    static SharedObjectState* receiverStateOf(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object
    );
  };
}
