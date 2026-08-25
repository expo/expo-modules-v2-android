#include <expo-modules-v2/converter/decoders/JniDecode.h>

#include <expo-modules-v2/jni/JDynamicTypes.h>
#include <expo-modules-v2/converter/decoders/DynamicJniDecoder.h>
#include <expo-modules-v2/converter/decoders/JniDecoder.h>

namespace expo::modules::v2 {
  facebook::jsi::Value decodeFromJni(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object,
    const ExpectedType& type
  ) {
    if (object == nullptr) {
      return facebook::jsi::Value::null();
    }

    return type.visit<facebook::jsi::Value>(
      JniDecoder{.env = env, .rt = rt, .object = object}
    );
  }

  facebook::jsi::Value decodeFromJniDynamic(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object
  ) {
    if (object == nullptr) {
      return facebook::jsi::Value::null();
    }

    return visitCppType<facebook::jsi::Value>(
      JDynamicTypes::kindOf(env, object),
      DynamicJniDecoder{.env = env, .rt = rt, .object = object}
    );
  }
} // namespace expo::modules::v2
