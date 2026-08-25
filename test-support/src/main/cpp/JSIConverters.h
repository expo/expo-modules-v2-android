#pragma once

#include <jsi/jsi.h>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

namespace expo::modules::v2 {
  /**
 * Compile-time-typed conversion between a `facebook::jsi::Value` and a native C++ type.
 *
 * `JSIConverter<T>::fromJSI` / `::toJSI` is specialized for bool, int32_t, int64_t, float, double,
 * std::string, and the containers std::vector<T> and std::unordered_map<std::string, T>
 * (nestable). Use it through the [fromJSIValue] / [toJSIValue] free functions:
 *
 *   int n          = fromJSIValue<int>(rt, value);
 *   auto v         = fromJSIValue<std::vector<int>>(rt, value);
 *   facebook::jsi::Value out = toJSIValue(rt, 42);
 *
 * An unsupported `T` is a compile error (the primary template is left undefined); a value of the
 * wrong JS shape throws `facebook::jsi::JSError` at runtime.
 *
 * Test-support only. The production bridge never knows its C++ types at compile time — it is
 * driven by an [ExpectedType] descriptor and goes through `converter/encoders/JniEncode.h` /
 * `converter/decoders/JniDecode.h` instead. This mechanism backs the `__nativeRoundTrip` host
 * function, which pins the leaf coercion rules those encoders are expected to match.
 */
  template<typename T, typename Enable = void>
  struct JSIConverter;

  /** Reads a `facebook::jsi::Value` as the C++ type `T`. Throws `facebook::jsi::JSError` on mismatch. */
  template<typename T>
  T fromJSIValue(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
    return JSIConverter<std::decay_t<T> >::fromJSI(rt, value);
  }

  /** Converts a native C++ value into a `facebook::jsi::Value`. */
  template<typename T>
  facebook::jsi::Value toJSIValue(facebook::jsi::Runtime& rt, T&& value) {
    return JSIConverter<std::decay_t<T> >::toJSI(rt, std::forward<T>(value));
  }

  /** Convenience overload so string literals don't need an explicit std::string. */
  inline facebook::jsi::Value toJSIValue(facebook::jsi::Runtime& rt, const char* value) {
    return facebook::jsi::String::createFromUtf8(rt, value);
  }

  namespace detail {
    inline double asNumber(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value, const char* what) {
      if (!value.isNumber()) {
        throw facebook::jsi::JSError(rt, std::string("Expected a number for ") + what);
      }
      return value.getNumber();
    }
  } // namespace detail

  template<>
  struct JSIConverter<bool> {
    static bool fromJSI(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
      if (!value.isBool()) {
        throw facebook::jsi::JSError(rt, "Expected a boolean");
      }
      return value.getBool();
    }

    static facebook::jsi::Value toJSI(facebook::jsi::Runtime&, bool value) { return facebook::jsi::Value(value); }
  };

  template<>
  struct JSIConverter<int32_t> {
    static int32_t fromJSI(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
      return static_cast<int32_t>(detail::asNumber(rt, value, "Int"));
    }

    static facebook::jsi::Value toJSI(facebook::jsi::Runtime&, int32_t value) {
      return facebook::jsi::Value(static_cast<double>(value));
    }
  };

  template<>
  struct JSIConverter<int64_t> {
    static int64_t fromJSI(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
      // TODO: values above 2^53 lose precision; revisit with BigInt support.
      return static_cast<int64_t>(detail::asNumber(rt, value, "Long"));
    }

    static facebook::jsi::Value toJSI(facebook::jsi::Runtime&, int64_t value) {
      return facebook::jsi::Value(static_cast<double>(value));
    }
  };

  template<>
  struct JSIConverter<float> {
    static float fromJSI(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
      return static_cast<float>(detail::asNumber(rt, value, "Float"));
    }

    static facebook::jsi::Value toJSI(facebook::jsi::Runtime&, float value) {
      return facebook::jsi::Value(static_cast<double>(value));
    }
  };

  template<>
  struct JSIConverter<double> {
    static double fromJSI(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
      return detail::asNumber(rt, value, "Double");
    }

    static facebook::jsi::Value toJSI(facebook::jsi::Runtime&, double value) { return facebook::jsi::Value(value); }
  };

  template<>
  struct JSIConverter<std::string> {
    static std::string fromJSI(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
      if (!value.isString()) {
        throw facebook::jsi::JSError(rt, "Expected a string");
      }
      return value.getString(rt).utf8(rt);
    }

    static facebook::jsi::Value toJSI(facebook::jsi::Runtime& rt, const std::string& value) {
      return facebook::jsi::String::createFromUtf8(rt, value);
    }
  };

  template<typename T>
  struct JSIConverter<std::vector<T> > {
    static std::vector<T> fromJSI(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
      if (!value.isObject() || !value.getObject(rt).isArray(rt)) {
        throw facebook::jsi::JSError(rt, "Expected an array");
      }
      facebook::jsi::Array array = value.getObject(rt).getArray(rt);
      const size_t size = array.size(rt);
      std::vector<T> result;
      result.reserve(size);
      for (size_t i = 0; i < size; i++) {
        result.push_back(JSIConverter<std::decay_t<T> >::fromJSI(rt, array.getValueAtIndex(rt, i)));
      }
      return result;
    }

    static facebook::jsi::Value toJSI(facebook::jsi::Runtime& rt, const std::vector<T>& value) {
      facebook::jsi::Array array(rt, value.size());
      for (size_t i = 0; i < value.size(); i++) {
        array.setValueAtIndex(rt, i, JSIConverter<std::decay_t<T> >::toJSI(rt, value[i]));
      }
      return array;
    }
  };

  template<typename T>
  struct JSIConverter<std::unordered_map<std::string, T> > {
    static std::unordered_map<std::string, T> fromJSI(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
      if (!value.isObject() || value.getObject(rt).isArray(rt)) {
        throw facebook::jsi::JSError(rt, "Expected an object");
      }
      facebook::jsi::Object object = value.getObject(rt);
      facebook::jsi::Array names = object.getPropertyNames(rt);
      const size_t size = names.size(rt);
      std::unordered_map<std::string, T> result;
      result.reserve(size);
      for (size_t i = 0; i < size; i++) {
        std::string key = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        facebook::jsi::Value prop = object.getProperty(rt, facebook::jsi::PropNameID::forUtf8(rt, key));
        result.emplace(std::move(key), JSIConverter<std::decay_t<T> >::fromJSI(rt, prop));
      }
      return result;
    }

    static facebook::jsi::Value toJSI(facebook::jsi::Runtime& rt, const std::unordered_map<std::string, T>& value) {
      facebook::jsi::Object object(rt);
      for (const auto& [key, val]: value) {
        object.setProperty(
          rt,
          facebook::jsi::PropNameID::forUtf8(rt, key),
          JSIConverter<std::decay_t<T> >::toJSI(rt, val)
        );
      }
      return object;
    }
  };
} // namespace expo::modules::v2
