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

    static std::shared_ptr<SharedObjectState> stateOf(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object
    );
  };
}
