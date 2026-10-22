#include <expo-modules-v2/jni/JTrampoline.h>

namespace expo::modules::v2 {
  kolibri::Ref<> JTrampoline::takeOverflowResult(JNIEnv* env) {
    return takeOverflowResult_(env);
  }

  kolibri::Ref<kolibri::JObjectArray> JTrampoline::prepareOverflowArguments(JNIEnv* env) {
    return prepareOverflowArguments_(env);
  }

  jint JTrampoline::writeSlotDynamic(JNIEnv* env, jobject value) {
    return writeSlotDynamic_(env, value);
  }

  kolibri::Ref<> JTrampoline::readSlotDynamic(JNIEnv* env, const jint payloadLength) {
    return readSlotDynamic_(env, payloadLength);
  }
}
