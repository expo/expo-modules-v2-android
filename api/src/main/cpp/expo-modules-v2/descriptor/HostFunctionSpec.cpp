#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

#include <stdexcept>

#include <kolibri/class.h>
#include <kolibri/exception.h>
#include <kolibri/signature.h>
#include <kolibri/utils.h>

namespace expo::modules::v2::descriptor {
  bool HostFunctionSpec::hasBufferedArgs() const {
    return std::ranges::any_of(
      argTypes,
      [](const ExpectedType& type) { return type.usesBuffer(); }
    );
  }

  bool HostFunctionSpec::needsLocalFrame() const {
    return std::ranges::any_of(
      argTypes,
      [](const ExpectedType& type) {
        if (type.usesBuffer()) {
          return false;
        }

        switch (type.kind()) {
          case CppType::BOOLEAN:
          case CppType::INT:
          case CppType::LONG:
          case CppType::FLOAT:
          case CppType::DOUBLE:
            return false;
          default:
            return true;
        }
      }
    );
  }

  std::string HostFunctionSpec::functionSignature() const {
    std::string signature = "(";
    for (const ExpectedType& type: argTypes) {
      if (type.usesBuffer()) {
        continue;
      }

      auto nextArgSignature = type.jniDescriptor();
      signature += nextArgSignature;
    }

    if (hasBufferedArgs()) {
      signature += kolibri::jni_descriptor_string_v<jint>(); // payloadLength
    }

    if (async) {
      // The promise comes last, after payloadLength, which is the order TrampolinePoet declares.
      // An async trampoline hands its result to the promise, so it always returns void.
      signature += kPromiseDescriptor;
      signature += ")";
      signature += kolibri::jni_descriptor_string_v<void>();
      return signature;
    }

    signature += ")";

    if (returnType.usesBuffer()) {
      signature += kolibri::jni_descriptor_string_v<jint>();
    } else {
      auto returnTypeSignature = returnType.jniDescriptor();
      if (returnType.kind() == CppType::UNIT) {
        signature += kolibri::jni_descriptor_string_v<void>();
      } else {
        signature += returnTypeSignature;
      }
    }
    return signature;
  }

  void HostFunctionSpec::resolveFunction(JNIEnv* env, jclass clazz) {
    if (method != nullptr) {
      return;
    }

    const std::string signature = functionSignature();
    method = env->GetMethodID(clazz, methodName.c_str(), signature.c_str());
    kolibri::checkAndThrowPending(env);
    if (method == nullptr) {
      throw std::runtime_error(
        "No method " + methodName + signature + " on " + kolibri::describeClass(env, clazz)
      );
    }
  }
} // namespace expo::modules::v2::descriptor
