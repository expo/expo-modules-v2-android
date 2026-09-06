#include <expo-modules-v2/descriptor/StaticFunctionSpec.h>

#include <stdexcept>
#include <string>

#include <kolibri/class.h>
#include <kolibri/exception.h>
#include <kolibri/utils.h>

namespace expo::modules::v2::descriptor {
  void StaticFunctionSpec::resolveFunction(JNIEnv* env, jclass clazz) {
    if (method != nullptr) {
      return;
    }

    const std::string signature = functionSignature();
    method = env->GetStaticMethodID(clazz, methodName.c_str(), signature.c_str());
    kolibri::checkAndThrowPending(env);
    if (method == nullptr) {
      throw std::runtime_error(
        "No static method " + methodName + signature + " on " +
        kolibri::describeClass(env, clazz)
      );
    }
  }
}
