#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

#include <stdexcept>
#include <string>
#include <utility>

#include <kolibri/class.h>
#include <kolibri/env.h>
#include <kolibri/exception.h>
#include <kolibri/utils.h>

namespace expo::modules::v2::descriptor {
  HostFunctionSpec::HostFunctionSpec(FunctionSpec spec)
    : FunctionSpec(std::move(spec)),
      invoker_(
        selectFunctionInvoker(
          returnType,
          hasBufferedArgs(),
          needsLocalFrame(),
          async
        )
      ) {
  }

  facebook::jsi::Value HostFunctionSpec::invoke(
    facebook::jsi::Runtime& rt,
    const jobject receiver,
    const facebook::jsi::Value* args,
    const size_t count
  ) const {
    try {
      JNIEnv* env = kolibri::getEnv();
      resolve(env);
      return invoker_(rt, env, *this, receiver, args, count);
    } catch (const facebook::jsi::JSError&) {
      throw;
    } catch (const std::exception& e) {
      throw facebook::jsi::JSError(rt, std::string("native call failed: ") + e.what());
    }
  }

  void HostFunctionSpec::resolve(JNIEnv* env) const {
    if (method != nullptr) [[likely]] {
      return;
    }

    const std::string signature = functionSignature();
    const jmethodID resolved = env->GetMethodID(declaringClass, methodName.c_str(), signature.c_str());
    kolibri::checkAndThrowPending(env);
    if (resolved == nullptr) {
      throw std::runtime_error(
        "No method " + methodName + signature + " on " + kolibri::describeClass(env, declaringClass)
      );
    }
    method = resolved;
  }
}
