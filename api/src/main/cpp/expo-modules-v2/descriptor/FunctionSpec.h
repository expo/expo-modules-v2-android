#pragma once

#include <jni.h>

#include <string>
#include <string_view>
#include <vector>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2::descriptor {
  // MUST stay in sync with Kotlin `ModuleFunctionDefinition.FLAG_ASYNC`
  inline constexpr int kFunctionFlagAsync = 1;

  struct FunctionSpec {
    // MUST stay in sync with Kotlin `Trampoline.MAX_ARGUMENTS`.
    static constexpr size_t kMaxArgs = 8;

    /** The trailing parameter of an async trampoline. */
    static constexpr std::string_view kPromiseDescriptor = "Lio/github/expo/modules/v2/async/Promise;";

    std::string name;
    std::string methodName;
    std::vector<ExpectedType> argTypes;
    ExpectedType returnType;

    bool async = false;

    jclass declaringClass = nullptr;

    /** The method id, filled by the derived spec's `resolve` on first use. A cache, not an input. */
    mutable jmethodID method = nullptr;

    [[nodiscard]] bool hasBufferedArgs() const;

    [[nodiscard]] bool needsLocalFrame() const;

  protected:
    [[nodiscard]] std::string functionSignature() const;
  };
}
