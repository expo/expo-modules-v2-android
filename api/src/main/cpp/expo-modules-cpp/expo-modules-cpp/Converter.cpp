#include <expo-modules-cpp/Converter.h>

#include <charconv>
#include <cmath>
#include <memory>

#include <expo-jsi/ByteArrayBuffer.h>

namespace expo::modules::v2::cpp::detail {
  std::string formatNumber(const double number) {
    // The spellings JavaScript uses, since JavaScript is where the number came from.
    if (std::isnan(number)) {
      return "NaN";
    }
    if (std::isinf(number)) {
      return number > 0 ? "Infinity" : "-Infinity";
    }

    // `std::to_string(double)` keeps six decimals, which would print 1e20 as a 21-digit number and
    // a fraction as noise; the shortest text that reads back as the same number is what JavaScript
    // prints, give or take the exponent's spelling.
    char buffer[40];
    const auto written = std::to_chars(buffer, buffer + sizeof(buffer), number);
    return std::string(buffer, written.ptr);
  }

  void throwOutOfRange(const double got, const double min, const double max) {
    throw ConversionError(
      "expected an integer between " + formatNumber(min) + " and " + formatNumber(max) + ", got " + formatNumber(got)
    );
  }

  void throwWrongLength(
    js::Runtime& rt,
    const js::Value& value,
    const size_t expected,
    const size_t got
  ) {
    (void) rt;
    (void) value;
    throw ConversionError(
      "expected an array of " + std::to_string(expected) + " element(s), got " + std::to_string(got)
    );
  }

  ByteView byteViewOf(js::Runtime& rt, const js::Value& value, const char* const what) {
    if (value.isObject()) {
      js::Object object = value.getObject(rt);
      if (object.isArrayBuffer(rt)) {
        js::ArrayBuffer buffer = object.getArrayBuffer(rt);
        return {.data = buffer.data(rt), .size = buffer.size(rt)};
      }

      const bool isView = rt.global()
        .getPropertyAsObject(rt, "ArrayBuffer")
        .getPropertyAsFunction(rt, "isView")
        .call(rt, value)
        .getBool();
      if (isView) {
        return byteViewOfView(rt, object);
      }
    }

    throwExpected(rt, what, value);
  }

  ByteView byteViewOfView(js::Runtime& rt, const js::Object& view) {
    const js::Value bufferValue = view.getProperty(rt, "buffer");
    const js::Value offsetValue = view.getProperty(rt, "byteOffset");
    const js::Value lengthValue = view.getProperty(rt, "byteLength");
    if (!bufferValue.isObject() || !bufferValue.getObject(rt).isArrayBuffer(rt) || !offsetValue.isNumber() ||
        !lengthValue.isNumber()) [[unlikely]] {
      throw ConversionError("this view's buffer, byteOffset or byteLength is not what a typed array has");
    }

    js::ArrayBuffer buffer = bufferValue.getObject(rt).getArrayBuffer(rt);
    const double size = static_cast<double>(buffer.size(rt));
    const double offset = offsetValue.getNumber();
    const double length = lengthValue.getNumber();
    // Written so that a NaN fails: every comparison with it is false.
    const bool inside = offset >= 0 && length >= 0 && offset <= size && length <= size - offset &&
                        offset == std::floor(offset) && length == std::floor(length);
    if (!inside) [[unlikely]] {
      throw ConversionError("this view's byteOffset and byteLength do not fit inside its buffer");
    }
    return {.data = buffer.data(rt) + static_cast<size_t>(offset), .size = static_cast<size_t>(length)};
  }

  js::Value createArrayBuffer(js::Runtime& rt, const uint8_t* const data, const size_t size) {
    auto buffer = std::make_shared<::expo::jsi::ByteArrayBuffer>(data, size);
    return js::Value(rt, js::ArrayBuffer(rt, std::move(buffer)));
  }
} // namespace expo::modules::v2::cpp::detail

namespace expo::modules::v2::cpp::detail {
  void throwInvalidEnum(const std::string_view name, const std::string& got, const std::string& accepted) {
    throw ConversionError(got + " is not a valid " + std::string(name) + ". Pass one of: " + accepted);
  }

  void throwUnknownEnumValue(const std::string_view name) {
    throw ConversionError("this value is not an entry of " + std::string(name) + " that EXPO_ENUM lists");
  }

  void throwMissingField(const std::string_view record, const std::string_view field) {
    throw ConversionError(
      "Record '" + std::string(record) + "': field '" + std::string(field) +
      "' is null or missing but declared non-nullable"
    );
  }

  void rethrowFieldError(const std::string_view record, const std::string_view field, const ConversionError& error) {
    throw ConversionError("Record '" + std::string(record) + "': field '" + std::string(field) + "': " + error.what());
  }
} // namespace expo::modules::v2::cpp::detail
