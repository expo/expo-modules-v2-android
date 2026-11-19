#include <expo-modules-v2/jni/JDynamicTypes.h>

#include <stdexcept>

#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  CppType JDynamicTypes::kindOf(JNIEnv* env, jobject obj) {
    const jint raw = kindOf_(env, obj);
    if (raw < 0) {
      throwRuntimeError({
        "Cannot convert Java object to a JS value: unsupported class. If it is a record, its "
        "codec is not registered - declare the codec as the record class's companion object with "
        "`init { RecordRegistry.register(this) }`.",
      });
    }
    return static_cast<CppType>(raw);
  }
}
