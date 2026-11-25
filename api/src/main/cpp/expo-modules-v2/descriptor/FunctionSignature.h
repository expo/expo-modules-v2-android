#pragma once

#include <vector>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  /** The parameters and result of a JavaScript function, as its Kotlin lambda declares them. */
  struct FunctionSignature {
    std::vector<ExpectedType> params;
    ExpectedType returns;
    /** Whether the lambda is `suspend`. Only names the type: every call awaits a promise. */
    bool isSuspend;
  };
} // namespace expo::modules::v2
