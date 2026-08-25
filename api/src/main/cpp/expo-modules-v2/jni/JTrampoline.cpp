#include <expo-modules-v2/jni/JTrampoline.h>

namespace expo::modules::v2 {
  kolibri::Ref<> JTrampoline::takeOverflowResult(JNIEnv* env) {
    return takeOverflowResult_(env);
  }

  kolibri::Ref<kolibri::JObjectArray> JTrampoline::prepareOverflowArguments(JNIEnv* env) {
    return prepareOverflowArguments_(env);
  }
}
