#pragma once

#include <cstdint>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/Errors.h>
#include <expo-modules-cpp/NativeObject.h>
#include <expo-modules-cpp/internal/Specs.h>

namespace expo::modules::v2::cpp::detail {
  /** Where in a call a conversion failed, which decides how the failure is reported. */
  enum class Phase : uint8_t { Decoding, Running, Encoding };

  /**
   * What every exported function's host function calls: [spec]'s invoker, with the one `try` that
   * turns whatever the native code throws into the error JavaScript catches. It is out of the
   * templates so that it exists once, and not once per export.
   */
  EXPO_API js::Value callFunction(
    const FunctionSpec& spec,
    js::Runtime& rt,
    const js::Value& thisValue,
    const js::Value* args,
    size_t count,
    NativeObject* owner = nullptr
  );

  /** `<name> expects N argument(s), but received M`; for a call that leaves out more than the optional ones. */
  [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwArity(
    const FunctionSpec& spec,
    size_t received,
    size_t declared
  );

  /**
   * Reports a conversion failure from inside a catch block: a bad argument names the function and
   * which argument, a bad result names the function, and a failure in the native code itself is
   * not ours to describe and is rethrown as it was.
   */
  [[noreturn]] EXPO_COLD EXPO_NOINLINE void rethrowConversion(
    const FunctionSpec& spec,
    Phase phase,
    size_t argument,
    const ConversionError& error
  );

  /**
   * The object a member of a shared object class was called on, checked to be a live instance of
   * the class the member belongs to. Throws the error JavaScript sees otherwise.
   */
  EXPO_API NativeObject& receiverOf(const CallFrame& frame, const FunctionSpec& spec);

  /** Logs the exception being handled, under [what]. Must be called from a catch block. */
  EXPO_COLD EXPO_NOINLINE void logCurrentException(const char* what);

  /** The `undefined` an omitted argument stands for. */
  const js::Value& undefinedValue();
} // namespace expo::modules::v2::cpp::detail
