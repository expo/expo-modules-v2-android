#pragma once

#include <stdexcept>
#include <string>

#include <expo-modules-cpp/Defines.h>

namespace expo::modules::v2::cpp {
  /**
   * An error JavaScript sees as an `Error` carrying a `code`: Kotlin's `JavaScriptThrowable`.
   *
   * ```cpp
   * throw cpp::Exception("ERR_NOT_FOUND", "no such file: " + path);
   * ```
   *
   * Any other `std::exception` reaches JavaScript as an `Error` with its `what()` as the message
   * and no `code`.
   */
  class EXPO_API Exception : public std::runtime_error {
  public:
    Exception(std::string code, const std::string& message);

    /** An error with a message and no `code`. */
    explicit Exception(const std::string& message);

    [[nodiscard]] const std::string& code() const noexcept { return code_; }

  private:
    std::string code_;
  };

  /**
   * A JavaScript value did not have the shape a native type needs. The message completes the
   * sentence "expected ...": `"a number, got a string"`.
   *
   * Thrown by converters; the code that called them adds where it happened ("argument 2 of
   * 'add'"), so a converter never has to know.
   */
  class EXPO_API ConversionError : public std::runtime_error {
  public:
    explicit ConversionError(const std::string& message);
  };
} // namespace expo::modules::v2::cpp

namespace expo::modules::v2::cpp::detail {
  /**
   * A JavaScript `Error` for a native failure: [message] as its message and [code] as its `code`
   * unless it is empty. The same shape the Kotlin bridge reports, minus the Kotlin stack.
   */
  js::Object createError(js::Runtime& rt, const std::string& code, const std::string& message);

  /** Throws a `js::JSError` for [message]. */
  [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwJSError(js::Runtime& rt, const std::string& message);

  /** Throws a `ConversionError`: "expected [expected], got [what value is]". */
  [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwExpected(
    js::Runtime& rt,
    const char* expected,
    const js::Value& value
  );

  /** Throws a `ConversionError` with [message] as is. */
  [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwConversion(const std::string& message);

  /**
   * The JavaScript value for the exception being handled: the value of a `js::JSError`, an `Error`
   * with the `code` and message of a `cpp::Exception`, an `Error` with the message of any other
   * `std::exception`. Must be called from a catch block.
   */
  js::Value currentExceptionToError(js::Runtime& rt);

  /**
   * Rethrows the exception being handled as the `js::JSError` JavaScript catches: the original
   * if it already is one, [currentExceptionToError] otherwise. Must be called from a catch block.
   */
  [[noreturn]] EXPO_COLD EXPO_NOINLINE void rethrowAsJSError(js::Runtime& rt);

  /** "a number", "a string", "an array", ... - how a JavaScript value reads in an error message. */
  const char* describe(js::Runtime& rt, const js::Value& value);
} // namespace expo::modules::v2::cpp::detail
