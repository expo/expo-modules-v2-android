#include <expo-modules-cpp/Errors.h>

#include <exception>
#include <utility>

namespace expo::modules::v2::cpp {
  Exception::Exception(std::string code, const std::string& message)
    : std::runtime_error(message), code_(std::move(code)) {
  }

  Exception::Exception(const std::string& message) : std::runtime_error(message) {
  }

  ConversionError::ConversionError(const std::string& message) : std::runtime_error(message) {
  }
} // namespace expo::modules::v2::cpp

namespace expo::modules::v2::cpp::detail {
  js::Object createError(js::Runtime& rt, const std::string& code, const std::string& message) {
    js::Object error = rt.global()
      .getPropertyAsFunction(rt, "Error")
      .callAsConstructor(rt, js::String::createFromUtf8(rt, message))
      .getObject(rt);
    if (!code.empty()) {
      error.setProperty(rt, "code", js::String::createFromUtf8(rt, code));
    }
    return error;
  }

  void throwJSError(js::Runtime& rt, const std::string& message) {
    throw js::JSError(rt, message);
  }

  const char* describe(js::Runtime& rt, const js::Value& value) {
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
    if (value.isBigInt()) {
      return "a bigint";
    }
    if (value.isSymbol()) {
      return "a symbol";
    }

    const js::Object object = value.getObject(rt);
    if (object.isFunction(rt)) {
      return "a function";
    }
    if (object.isArray(rt)) {
      return "an array";
    }
    if (object.isArrayBuffer(rt)) {
      return "an ArrayBuffer";
    }
    return "an object";
  }

  void throwExpected(js::Runtime& rt, const char* expected, const js::Value& value) {
    std::string message = "expected ";
    message += expected;
    message += ", got ";
    message += describe(rt, value);
    throw ConversionError(message);
  }

  void throwConversion(const std::string& message) {
    throw ConversionError(message);
  }

  js::Value currentExceptionToError(js::Runtime& rt) {
    try {
      throw;
    } catch (const js::JSError& error) {
      return js::Value(rt, error.value());
    } catch (const Exception& exception) {
      return js::Value(rt, createError(rt, exception.code(), exception.what()));
    } catch (const std::exception& exception) {
      return js::Value(rt, createError(rt, "", exception.what()));
    } catch (...) {
      return js::Value(
        rt,
        createError(rt, "", "A native function threw something that is not an exception")
      );
    }
  }

  void rethrowAsJSError(js::Runtime& rt) {
    try {
      throw;
    } catch (const js::JSError&) {
      // Already what JavaScript catches: it keeps its own stack and value.
      throw;
    } catch (...) {
      throw js::JSError(rt, currentExceptionToError(rt));
    }
  }
} // namespace expo::modules::v2::cpp::detail
