#include <expo-modules-v2/utils/Throw.h>

#include <stdexcept>
#include <string>

namespace expo::modules::v2 {
  namespace {
    // TODO(@lukmccall): research if that's the best way of doing it
    std::string concat(const std::initializer_list<std::string_view> parts) {
      size_t size = 0;
      for (const std::string_view part: parts) {
        size += part.size();
      }

      std::string message;
      message.reserve(size);
      for (const std::string_view part: parts) {
        message.append(part);
      }
      return message;
    }

    std::string_view kindOf(const facebook::jsi::Value& value) {
      if (value.isUndefined()) {
        return "undefined";
      }
      if (value.isNull()) {
        return "null";
      }
      if (value.isBool()) {
        return "a boolean";
      }
      if (value.isNumber()) {
        return "a number";
      }
      if (value.isString()) {
        return "a string";
      }
      if (value.isSymbol()) {
        return "a symbol";
      }
      if (value.isObject()) {
        return "an object";
      }
      return "a bigint";
    }
  } // namespace

  COLD NOINLINE void throwJSError(
    facebook::jsi::Runtime& rt,
    const std::initializer_list<std::string_view> parts
  ) {
    throw facebook::jsi::JSError(rt, concat(parts));
  }

  COLD NOINLINE void throwTypeMismatch(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const std::string_view expected
  ) {
    throw facebook::jsi::JSError(rt, concat({"Expected ", expected, ", got ", kindOf(value)}));
  }

  COLD NOINLINE void throwInvalidArgument(const std::initializer_list<std::string_view> parts) {
    throw std::invalid_argument(concat(parts));
  }

  COLD NOINLINE void throwRuntimeError(const std::initializer_list<std::string_view> parts) {
    throw std::runtime_error(concat(parts));
  }
} // namespace expo::modules::v2
