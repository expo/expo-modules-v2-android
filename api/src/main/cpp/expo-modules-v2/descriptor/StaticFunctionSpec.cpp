#include <expo-modules-v2/descriptor/StaticFunctionSpec.h>

#include <stdexcept>
#include <string>
#include <utility>

#include <kolibri/class.h>
#include <kolibri/env.h>
#include <kolibri/exception.h>
#include <kolibri/utils.h>

#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2::descriptor {
  StaticFunctionSpec::StaticFunctionSpec(FunctionSpec spec)
    : FunctionSpec(std::move(spec)),
      invoker_(selectStaticObjectInvoker(hasBufferedArgs(), needsLocalFrame())) {
  }

  facebook::jsi::Value StaticFunctionSpec::invoke(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value* args,
    const size_t count
  ) const {
    try {
      JNIEnv* env = kolibri::getEnv();
      resolve(env);
      return invoker_(rt, env, *this, /* receiver */ nullptr, args, count);
    } catch (const facebook::jsi::JSError&) {
      throw;
    } catch (const ConversionError& e) {
      // An argument or a result that does not fit: JavaScript sees the conversion's own message.
      throwJSError(rt, {e.what()});
    } catch (const std::exception& e) {
      throwJSError(rt, {"native call failed: ", e.what()});
    }
  }

  void StaticFunctionSpec::resolve(JNIEnv* env) const {
    if (method != nullptr) [[likely]] {
      return;
    }

    const std::string signature = functionSignature();
    const jmethodID resolved = env->GetStaticMethodID(
      declaringClass,
      methodName.c_str(),
      signature.c_str()
    );
    kolibri::checkAndThrowPending(env);
    if (resolved == nullptr) {
      throwRuntimeError({
        "No static method ",
        methodName,
        signature,
        " on ",
        kolibri::describeClass(env, declaringClass),
      });
    }
    method = resolved;
  }
}
