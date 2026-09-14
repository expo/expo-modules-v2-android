#include <expo-modules-v2/descriptor/FunctionSpec.h>

#include <algorithm>
#include <stdexcept>

#include <kolibri/class.h>
#include <kolibri/signature.h>
#include <kolibri/utils.h>

namespace expo::modules::v2::descriptor {
  bool FunctionSpec::hasBufferedArgs() const {
    return std::ranges::any_of(
      argTypes,
      [](const ExpectedType& type) { return type.usesBuffer(); }
    );
  }

  bool FunctionSpec::needsLocalFrame() const {
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

  std::string FunctionSpec::functionSignature() const {
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

}
