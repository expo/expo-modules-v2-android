#pragma once

#include <stdexcept>
#include <initializer_list>
#include <string_view>

#include <jsi/jsi.h>

#include <kolibri/defines.h>

namespace expo::modules::v2 {
  /**
   * What a conversion between a JavaScript value and a Java one throws when the value does not fit.
   * It makes no JavaScript object, so it is cheap to throw and catch: a union catches it and offers
   * the value to its next variant. Where JavaScript called in - `HostFunctionSpec::invoke` and
   * `StaticFunctionSpec::invoke` - it becomes a `JSError` with the same message.
   */
  class ConversionError final : public std::runtime_error {
  public:
    using std::runtime_error::runtime_error;
  };

  NORETURN void throwJSError(
    facebook::jsi::Runtime& rt,
    std::initializer_list<std::string_view> parts
  );

  /** Throws a [ConversionError] with the [parts] of its message. */
  NORETURN void throwConversionError(std::initializer_list<std::string_view> parts);

  /** Throws a [ConversionError] that names the JS kind of `value` next to the `expected` one. */
  NORETURN void throwTypeMismatch(const facebook::jsi::Value& value, std::string_view expected);

  NORETURN void throwInvalidArgument(std::initializer_list<std::string_view> parts);

  NORETURN void throwRuntimeError(std::initializer_list<std::string_view> parts);
}
