#pragma once

#include <jni.h>

#include <string>
#include <string_view>
#include <vector>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2::descriptor {
  // MUST stay in sync with Kotlin `ModuleFunctionDefinition.FLAG_ASYNC`
  inline constexpr int kFunctionFlagAsync = 1;

  struct HostFunctionSpec {
    // MUST stay in sync with Kotlin `Trampoline.MAX_ARGUMENTS`.
    static constexpr size_t kMaxArgs = 8;

    /** The trailing parameter of an async trampoline. */
    static constexpr std::string_view kPromiseDescriptor = "Lio/github/expo/modules/v2/async/Promise;";

    std::string name;
    std::string methodName;
    std::vector<ExpectedType> argTypes;
    ExpectedType returnType;

    bool async = false;

    jmethodID method = nullptr;

    /**
     * The class [method] was resolved against, which is the receiver's own class.
     *
     * A borrowed pointer: [FunctionBinder] owns the global reference, and the host function it
     * installs already outlives nothing longer than the binder.
     */
    jclass declaringClass = nullptr;

    [[nodiscard]] bool hasBufferedArgs() const;

    [[nodiscard]] bool needsLocalFrame() const;

    void resolveFunction(JNIEnv* env, jclass clazz);

  private:
    [[nodiscard]] std::string functionSignature() const;
  };
}
