#pragma once

#include <cstddef>
#include <string>
#include <utility>
#include <variant>
#include <vector>

#include <expo-modules-cpp/Converter.h>

namespace expo::modules::v2::cpp {
  /**
   * Any JSON-like JavaScript value, for an export that does not know its shape up front: `null`,
   * a boolean, a number, a string, an array or an object. Kotlin's `Any?`.
   *
   * An object keeps its keys in the order JavaScript lists them. A function, a symbol or a
   * typed array does not convert; take a `js::Value` for those.
   */
  class Any {
  public:
    using Array = std::vector<Any>;
    using Object = std::vector<std::pair<std::string, Any>>;
    using Storage = std::variant<std::nullptr_t, bool, double, std::string, Array, Object>;

    Any() : value_(nullptr) {
    }

    Any(std::nullptr_t) : value_(nullptr) {
    }

    Any(const bool value) : value_(value) {
    }

    Any(const int value) : value_(static_cast<double>(value)) {
    }

    Any(const double value) : value_(value) {
    }

    Any(const char* const value) : value_(std::string(value)) {
    }

    Any(std::string value) : value_(std::move(value)) {
    }

    Any(Array value) : value_(std::move(value)) {
    }

    Any(Object value) : value_(std::move(value)) {
    }

    [[nodiscard]] bool isNull() const noexcept { return std::holds_alternative<std::nullptr_t>(value_); }

    [[nodiscard]] bool isBool() const noexcept { return std::holds_alternative<bool>(value_); }

    [[nodiscard]] bool isNumber() const noexcept { return std::holds_alternative<double>(value_); }

    [[nodiscard]] bool isString() const noexcept { return std::holds_alternative<std::string>(value_); }

    [[nodiscard]] bool isArray() const noexcept { return std::holds_alternative<Array>(value_); }

    [[nodiscard]] bool isObject() const noexcept { return std::holds_alternative<Object>(value_); }

    /** The held value as [T]; throws `std::bad_variant_access` when it is another kind. */
    [[nodiscard]] bool asBool() const { return std::get<bool>(value_); }

    [[nodiscard]] double asNumber() const { return std::get<double>(value_); }

    [[nodiscard]] const std::string& asString() const { return std::get<std::string>(value_); }

    [[nodiscard]] const Array& asArray() const { return std::get<Array>(value_); }

    [[nodiscard]] const Object& asObject() const { return std::get<Object>(value_); }

    [[nodiscard]] const Storage& storage() const noexcept { return value_; }

  private:
    Storage value_;
  };

  namespace detail {
    /** The most nested structure that converts; deeper is a cycle, or something nobody meant to send. */
    inline constexpr int kMaxAnyDepth = 128;

    Any anyFromJS(js::Runtime& rt, const js::Value& value, int depth);

    js::Value anyToJS(js::Runtime& rt, const Any& value, int depth);
  } // namespace detail

  template<>
  struct Converter<Any> {
    static constexpr bool kOptional = true;

    static Any fromJS(js::Runtime& rt, const js::Value& value) { return detail::anyFromJS(rt, value, 0); }

    static js::Value toJS(js::Runtime& rt, const Any& value) { return detail::anyToJS(rt, value, 0); }
  };
} // namespace expo::modules::v2::cpp
