#pragma once

#include <array>
#include <chrono>
#include <cmath>
#include <concepts>
#include <cstdint>
#include <filesystem>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <variant>
#include <vector>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/Errors.h>
#include <expo-modules-cpp/Traits.h>

namespace expo::modules::v2::cpp {
  /**
   * How a C++ type crosses to and from JavaScript. Specialize it to teach the library a type of
   * your own; every export, event payload and promise result goes through it.
   *
   * ```cpp
   * template<>
   * struct cpp::Converter<Color> {
   *   static Color fromJS(js::Runtime& rt, const js::Value& value);  // throws cpp::ConversionError
   *   static js::Value toJS(js::Runtime& rt, const Color& value);
   * };
   * ```
   *
   * A record is described with `EXPO_RECORD`, an enum with `EXPO_ENUM`, and a shared object by
   * deriving from `cpp::SharedObject`; none of them needs a specialization.
   *
   * [fromJS] receives the value exactly as JavaScript passed it - possibly `undefined` or `null` -
   * and throws a `ConversionError` (see `detail::throwExpected`) when it is not what the type
   * needs. A converter that accepts a missing value declares `static constexpr bool kOptional =
   * true`, which is what lets a trailing parameter of that type be left out of a call.
   */
  template<typename T>
  struct Converter {
    static_assert(
      sizeof(T) == 0,
      "No cpp::Converter for this type. Use a type the library knows (numbers, std::string, "
      "std::vector, std::optional, std::map, ...), describe a struct with EXPO_RECORD and an enum "
      "with EXPO_ENUM - written in the same namespace as the type, after it - or specialize "
      "cpp::Converter<T>."
    );
  };

  /** Converts [value] to [T]. Throws `ConversionError` when it does not fit. */
  template<typename T>
  [[nodiscard]] decltype(auto) fromJS(js::Runtime& rt, const js::Value& value) {
    return Converter<detail::Plain<T>>::fromJS(rt, value);
  }

  /** Converts [value] to the JavaScript value the library hands out for its type. */
  template<typename T>
  [[nodiscard]] js::Value toJS(js::Runtime& rt, T&& value) {
    return Converter<detail::Plain<T>>::toJS(rt, std::forward<T>(value));
  }
} // namespace expo::modules::v2::cpp

namespace expo::modules::v2::cpp::detail {
  /** Whether a call may leave out an argument of type [T]: `Converter<T>::kOptional`. */
  template<typename T>
  constexpr bool acceptsMissing() {
    if constexpr (requires { Converter<T>::kOptional; }) {
      return Converter<T>::kOptional;
    } else {
      return false;
    }
  }

  /**
   * What a value of type [T] looks like in JavaScript, for an error that lists the alternatives of
   * a `std::variant`: "a number", "a string", "an array", ... and the C++ name for anything else.
   */
  template<typename T>
  constexpr std::string_view expectation() {
    if constexpr (std::is_same_v<T, bool>) {
      return "a boolean";
    } else if constexpr (std::is_arithmetic_v<T>) {
      return "a number";
    } else if constexpr (std::is_same_v<T, std::string> || std::is_same_v<T, std::filesystem::path>) {
      return "a string";
    } else if constexpr (kIsSpecialization<T, std::vector> || kIsSpecialization<T, std::set> ||
                         kIsSpecialization<T, std::unordered_set> || kIsSpecialization<T, std::tuple> ||
                         kIsSpecialization<T, std::pair>) {
      return "an array";
    } else if constexpr (kIsSpecialization<T, std::map> || kIsSpecialization<T, std::unordered_map>) {
      return "an object";
    } else {
      return typeName<T>();
    }
  }

  [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwOutOfRange(double got, double min, double max);

  /** [number] for an error message, the way JavaScript spells it where it can: `NaN`, `Infinity`, `3.5`. */
  std::string formatNumber(double number);

  [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwWrongLength(
    js::Runtime& rt,
    const js::Value& value,
    size_t expected,
    size_t got
  );

  /**
   * [number] as an integer of type [T]. A fraction is dropped, toward zero, as a cast from `double`
   * drops it - so `-0.5` is `0` for every type, unsigned ones included. Anything that does not fit -
   * NaN, infinity, out of range - is an error, where the cast would be undefined.
   */
  template<typename T>
  EXPO_ALWAYS_INLINE T toInteger(const double number) {
    constexpr double kMin = static_cast<double>(std::numeric_limits<T>::min());
    // One past the largest value: `max` itself rounds up to a power of two for 64-bit types, and
    // the comparison has to exclude that power of two.
    constexpr double kEnd = static_cast<double>(std::numeric_limits<T>::max()) + 1.0;
    // The fraction that is dropped makes the smallest value less strict: anything above `min - 1`
    // drops to `min` or more. For a 64-bit signed type `min - 1` is not a double, but nor is a
    // fraction that close to it, so `min` itself is the bound.
    constexpr bool kFractionsBelow = std::is_unsigned_v<T> || sizeof(T) < sizeof(int64_t);
    const bool aboveMin = kFractionsBelow ? number > kMin - 1.0 : number >= kMin;
    if (!(aboveMin && number < kEnd)) [[unlikely]] {
      throwOutOfRange(number, kMin, kEnd - 1.0);
    }
    return static_cast<T>(number);
  }

  /** The array [value] is, or an error. */
  inline js::Array expectArray(js::Runtime& rt, const js::Value& value) {
    if (value.isObject()) [[likely]] {
      js::Object object = value.getObject(rt);
      if (object.isArray(rt)) [[likely]] {
        return std::move(object).getArray(rt);
      }
    }
    throwExpected(rt, "an array", value);
  }

  inline js::Object expectObject(js::Runtime& rt, const js::Value& value) {
    if (!value.isObject()) [[unlikely]] {
      throwExpected(rt, "an object", value);
    }
    return value.getObject(rt);
  }

  /** The bytes of an ArrayBuffer, or of a typed array or DataView over one. Borrowed from JavaScript. */
  struct ByteView {
    uint8_t* data;
    size_t size;
  };

  /** Throws a ConversionError when [value] is none of them; [what] names what was expected. */
  ByteView byteViewOf(js::Runtime& rt, const js::Value& value, const char* what);

  /**
   * The bytes a typed array or `DataView` covers. The three properties that say where they are -
   * `buffer`, `byteOffset`, `byteLength` - are read like any others, and an object can define its
   * own, so they are checked against the buffer before they are believed.
   */
  ByteView byteViewOfView(js::Runtime& rt, const js::Object& view);

  /** A new ArrayBuffer holding a copy of the bytes. */
  js::Value createArrayBuffer(js::Runtime& rt, const uint8_t* data, size_t size);
} // namespace expo::modules::v2::cpp::detail

namespace expo::modules::v2::cpp {
  // --- scalars ---------------------------------------------------------------------------------

  template<>
  struct Converter<bool> {
    static bool fromJS(js::Runtime& rt, const js::Value& value) {
      if (!value.isBool()) [[unlikely]] {
        detail::throwExpected(rt, "a boolean", value);
      }
      return value.getBool();
    }

    static js::Value toJS(js::Runtime&, const bool value) { return js::Value(value); }
  };

  /**
   * Every integer type. JavaScript has one number type, so a 64-bit integer above 2^53 loses its
   * low bits on the way out, exactly as a Kotlin `Long` does.
   */
  template<std::integral T>
    requires(!std::same_as<T, bool>)
  struct Converter<T> {
    static T fromJS(js::Runtime& rt, const js::Value& value) {
      if (!value.isNumber()) [[unlikely]] {
        detail::throwExpected(rt, "a number", value);
      }
      return detail::toInteger<T>(value.getNumber());
    }

    static js::Value toJS(js::Runtime&, const T value) {
      return js::Value(static_cast<double>(value));
    }
  };

  template<std::floating_point T>
  struct Converter<T> {
    static T fromJS(js::Runtime& rt, const js::Value& value) {
      if (!value.isNumber()) [[unlikely]] {
        detail::throwExpected(rt, "a number", value);
      }
      return static_cast<T>(value.getNumber());
    }

    static js::Value toJS(js::Runtime&, const T value) {
      return js::Value(static_cast<double>(value));
    }
  };

  // --- text ------------------------------------------------------------------------------------

  template<>
  struct Converter<std::string> {
    static std::string fromJS(js::Runtime& rt, const js::Value& value) {
      if (!value.isString()) [[unlikely]] {
        detail::throwExpected(rt, "a string", value);
      }
      return value.getString(rt).utf8(rt);
    }

    static js::Value toJS(js::Runtime& rt, const std::string& value) {
      return js::Value(js::String::createFromUtf8(rt, value));
    }
  };

  /** Result only: a parameter cannot borrow the string, which JavaScript owns. */
  template<>
  struct Converter<std::string_view> {
    static js::Value toJS(js::Runtime& rt, const std::string_view value) {
      return js::Value(js::String::createFromUtf8(
        rt,
        reinterpret_cast<const uint8_t*>(value.data()),
        value.size()
      ));
    }
  };

  /** Result only. */
  template<>
  struct Converter<const char*> {
    static js::Value toJS(js::Runtime& rt, const char* const value) {
      return value == nullptr ? js::Value::null() : js::Value(js::String::createFromUtf8(rt, value));
    }
  };

  /** A file system path crosses as its string, the way Kotlin's `File` and `Path` do. */
  template<>
  struct Converter<std::filesystem::path> {
    static std::filesystem::path fromJS(js::Runtime& rt, const js::Value& value) {
      return std::filesystem::path(Converter<std::string>::fromJS(rt, value));
    }

    static js::Value toJS(js::Runtime& rt, const std::filesystem::path& value) {
      return Converter<std::string>::toJS(rt, value.string());
    }
  };

  /** A duration crosses as a number of seconds, the way Kotlin's `Duration` does. */
  template<typename Rep, typename Period>
  struct Converter<std::chrono::duration<Rep, Period>> {
    using Duration = std::chrono::duration<Rep, Period>;

    static Duration fromJS(js::Runtime& rt, const js::Value& value) {
      if (!value.isNumber()) [[unlikely]] {
        detail::throwExpected(rt, "a number of seconds", value);
      }
      const double seconds = value.getNumber();
      if constexpr (std::is_integral_v<Rep>) {
        // A cast of a double that does not fit is undefined - NaN, infinity and 1e300 among them -
        // so the count is checked like any integer's before it is made.
        const double count = std::chrono::duration<double, Period>(std::chrono::duration<double>(seconds)).count();
        return Duration(detail::toInteger<Rep>(count));
      } else {
        return std::chrono::duration_cast<Duration>(std::chrono::duration<double>(seconds));
      }
    }

    static js::Value toJS(js::Runtime&, const Duration& value) {
      return js::Value(std::chrono::duration<double>(value).count());
    }
  };

  // --- nothing and maybe-something -------------------------------------------------------------

  template<>
  struct Converter<std::nullptr_t> {
    static js::Value toJS(js::Runtime&, std::nullptr_t) { return js::Value::null(); }
  };

  /**
   * `undefined` and `null` are `std::nullopt`; a result without a value is `null`. A trailing
   * `std::optional` parameter can be left out of the call.
   */
  template<typename T>
  struct Converter<std::optional<T>> {
    static constexpr bool kOptional = true;

    static std::optional<T> fromJS(js::Runtime& rt, const js::Value& value) {
      if (value.isUndefined() || value.isNull()) {
        return std::nullopt;
      }
      return std::optional<T>(std::in_place, Converter<T>::fromJS(rt, value));
    }

    static js::Value toJS(js::Runtime& rt, const std::optional<T>& value) {
      return value.has_value() ? Converter<T>::toJS(rt, *value) : js::Value::null();
    }
  };

  // --- JavaScript values, as they are -----------------------------------------------------------

  template<>
  struct Converter<js::Value> {
    static constexpr bool kOptional = true;

    static js::Value fromJS(js::Runtime& rt, const js::Value& value) { return js::Value(rt, value); }

    static js::Value toJS(js::Runtime& rt, const js::Value& value) { return js::Value(rt, value); }

    static js::Value toJS(js::Runtime&, js::Value&& value) { return std::move(value); }
  };

  template<>
  struct Converter<js::Object> {
    static js::Object fromJS(js::Runtime& rt, const js::Value& value) {
      return detail::expectObject(rt, value);
    }

    static js::Value toJS(js::Runtime& rt, const js::Object& value) { return js::Value(rt, value); }

    static js::Value toJS(js::Runtime&, js::Object&& value) { return js::Value(std::move(value)); }
  };

  template<>
  struct Converter<js::Function> {
    static js::Function fromJS(js::Runtime& rt, const js::Value& value) {
      if (value.isObject()) [[likely]] {
        js::Object object = value.getObject(rt);
        if (object.isFunction(rt)) [[likely]] {
          return std::move(object).getFunction(rt);
        }
      }
      detail::throwExpected(rt, "a function", value);
    }

    static js::Value toJS(js::Runtime& rt, const js::Function& value) { return js::Value(rt, value); }

    static js::Value toJS(js::Runtime&, js::Function&& value) { return js::Value(std::move(value)); }
  };

  template<>
  struct Converter<js::Array> {
    static js::Array fromJS(js::Runtime& rt, const js::Value& value) {
      return detail::expectArray(rt, value);
    }

    static js::Value toJS(js::Runtime& rt, const js::Array& value) { return js::Value(rt, value); }

    static js::Value toJS(js::Runtime&, js::Array&& value) { return js::Value(std::move(value)); }
  };

  template<>
  struct Converter<js::ArrayBuffer> {
    static js::ArrayBuffer fromJS(js::Runtime& rt, const js::Value& value) {
      if (value.isObject()) [[likely]] {
        js::Object object = value.getObject(rt);
        if (object.isArrayBuffer(rt)) [[likely]] {
          return std::move(object).getArrayBuffer(rt);
        }
      }
      detail::throwExpected(rt, "an ArrayBuffer", value);
    }

    static js::Value toJS(js::Runtime& rt, const js::ArrayBuffer& value) {
      return js::Value(rt, value);
    }

    static js::Value toJS(js::Runtime&, js::ArrayBuffer&& value) {
      return js::Value(js::Object(std::move(value)));
    }
  };

  template<>
  struct Converter<js::String> {
    static js::String fromJS(js::Runtime& rt, const js::Value& value) {
      if (!value.isString()) [[unlikely]] {
        detail::throwExpected(rt, "a string", value);
      }
      return value.getString(rt);
    }

    static js::Value toJS(js::Runtime& rt, const js::String& value) { return js::Value(rt, value); }
  };

  // --- bytes -----------------------------------------------------------------------------------

  /**
   * A byte array is an ArrayBuffer in JavaScript, as a Kotlin `ByteArray` is. A parameter also
   * takes a typed array or a DataView, whose window of the buffer it copies. A list of small
   * numbers is a `std::vector<int>`.
   */
  template<>
  struct Converter<std::vector<uint8_t>> {
    static std::vector<uint8_t> fromJS(js::Runtime& rt, const js::Value& value) {
      const detail::ByteView view = detail::byteViewOf(
        rt,
        value,
        "an ArrayBuffer, a typed array such as a Uint8Array, or a DataView for a byte array"
      );
      return std::vector<uint8_t>(view.data, view.data + view.size);
    }

    static js::Value toJS(js::Runtime& rt, const std::vector<uint8_t>& value) {
      return detail::createArrayBuffer(rt, value.data(), value.size());
    }
  };

  // --- collections -----------------------------------------------------------------------------

  /** A JavaScript array. */
  template<typename T, typename Allocator>
    requires(!std::same_as<T, uint8_t>)
  struct Converter<std::vector<T, Allocator>> {
    static std::vector<T, Allocator> fromJS(js::Runtime& rt, const js::Value& value) {
      const js::Array array = detail::expectArray(rt, value);
      const size_t size = array.size(rt);
      std::vector<T, Allocator> result;
      result.reserve(size);
      for (size_t i = 0; i < size; i++) {
        result.push_back(Converter<T>::fromJS(rt, array.getValueAtIndex(rt, i)));
      }
      return result;
    }

    static js::Value toJS(js::Runtime& rt, const std::vector<T, Allocator>& value) {
      js::Array array(rt, value.size());
      size_t index = 0;
      for (const auto& element: value) {
        array.setValueAtIndex(rt, index++, Converter<T>::toJS(rt, element));
      }
      return js::Value(std::move(array));
    }
  };

  /** A JavaScript array of exactly N elements. */
  template<typename T, size_t N>
  struct Converter<std::array<T, N>> {
    static std::array<T, N> fromJS(js::Runtime& rt, const js::Value& value) {
      const js::Array array = detail::expectArray(rt, value);
      if (array.size(rt) != N) [[unlikely]] {
        detail::throwWrongLength(rt, value, N, array.size(rt));
      }
      return build(rt, array, std::make_index_sequence<N>{});
    }

    static js::Value toJS(js::Runtime& rt, const std::array<T, N>& value) {
      js::Array array(rt, N);
      for (size_t i = 0; i < N; i++) {
        array.setValueAtIndex(rt, i, Converter<T>::toJS(rt, value[i]));
      }
      return js::Value(std::move(array));
    }

  private:
    template<size_t... I>
    static std::array<T, N> build(js::Runtime& rt, const js::Array& array, std::index_sequence<I...>) {
      // A braced list evaluates left to right, so the elements are read in order.
      return std::array<T, N>{Converter<T>::fromJS(rt, array.getValueAtIndex(rt, I))...};
    }
  };

  /** A fixed-length JavaScript array, one converter per element. */
  template<typename... Ts>
  struct Converter<std::tuple<Ts...>> {
    static std::tuple<Ts...> fromJS(js::Runtime& rt, const js::Value& value) {
      const js::Array array = detail::expectArray(rt, value);
      if (array.size(rt) != sizeof...(Ts)) [[unlikely]] {
        detail::throwWrongLength(rt, value, sizeof...(Ts), array.size(rt));
      }
      return build(rt, array, std::index_sequence_for<Ts...>{});
    }

    static js::Value toJS(js::Runtime& rt, const std::tuple<Ts...>& value) {
      return std::apply(
        [&rt](const Ts&... elements) {
          return js::Value(js::Array::createWithElements(rt, Converter<Ts>::toJS(rt, elements)...));
        },
        value
      );
    }

  private:
    template<size_t... I>
    static std::tuple<Ts...> build(js::Runtime& rt, const js::Array& array, std::index_sequence<I...>) {
      return std::tuple<Ts...>{Converter<Ts>::fromJS(rt, array.getValueAtIndex(rt, I))...};
    }
  };

  template<typename A, typename B>
  struct Converter<std::pair<A, B>> {
    static std::pair<A, B> fromJS(js::Runtime& rt, const js::Value& value) {
      auto [first, second] = Converter<std::tuple<A, B>>::fromJS(rt, value);
      return {std::move(first), std::move(second)};
    }

    static js::Value toJS(js::Runtime& rt, const std::pair<A, B>& value) {
      return js::Value(js::Array::createWithElements(
        rt,
        Converter<A>::toJS(rt, value.first),
        Converter<B>::toJS(rt, value.second)
      ));
    }
  };

  /** A JavaScript array whose elements are unique. */
  template<typename T, typename Compare, typename Allocator>
  struct Converter<std::set<T, Compare, Allocator>> {
    static std::set<T, Compare, Allocator> fromJS(js::Runtime& rt, const js::Value& value) {
      const js::Array array = detail::expectArray(rt, value);
      const size_t size = array.size(rt);
      std::set<T, Compare, Allocator> result;
      for (size_t i = 0; i < size; i++) {
        result.insert(Converter<T>::fromJS(rt, array.getValueAtIndex(rt, i)));
      }
      return result;
    }

    static js::Value toJS(js::Runtime& rt, const std::set<T, Compare, Allocator>& value) {
      js::Array array(rt, value.size());
      size_t index = 0;
      for (const auto& element: value) {
        array.setValueAtIndex(rt, index++, Converter<T>::toJS(rt, element));
      }
      return js::Value(std::move(array));
    }
  };

  template<typename T, typename Hash, typename Equal, typename Allocator>
  struct Converter<std::unordered_set<T, Hash, Equal, Allocator>> {
    static std::unordered_set<T, Hash, Equal, Allocator> fromJS(js::Runtime& rt, const js::Value& value) {
      const js::Array array = detail::expectArray(rt, value);
      const size_t size = array.size(rt);
      std::unordered_set<T, Hash, Equal, Allocator> result;
      result.reserve(size);
      for (size_t i = 0; i < size; i++) {
        result.insert(Converter<T>::fromJS(rt, array.getValueAtIndex(rt, i)));
      }
      return result;
    }

    static js::Value toJS(js::Runtime& rt, const std::unordered_set<T, Hash, Equal, Allocator>& value) {
      js::Array array(rt, value.size());
      size_t index = 0;
      for (const auto& element: value) {
        array.setValueAtIndex(rt, index++, Converter<T>::toJS(rt, element));
      }
      return js::Value(std::move(array));
    }
  };
} // namespace expo::modules::v2::cpp

namespace expo::modules::v2::cpp::detail {
  template<typename Map>
  Map mapFromJS(js::Runtime& rt, const js::Value& value) {
    using Mapped = typename Map::mapped_type;
    const js::Object object = expectObject(rt, value);
    // The enumerable property names, as a `for...in` lists them.
    const js::Array keys = object.getPropertyNames(rt);
    const size_t size = keys.size(rt);

    Map result;
    for (size_t i = 0; i < size; i++) {
      const js::String key = keys.getValueAtIndex(rt, i).getString(rt);
      result.emplace(key.utf8(rt), Converter<Mapped>::fromJS(rt, object.getProperty(rt, key)));
    }
    return result;
  }

  template<typename Map>
  js::Value mapToJS(js::Runtime& rt, const Map& value) {
    js::Object object(rt);
    for (const auto& [key, item]: value) {
      object.setProperty(
        rt,
        js::String::createFromUtf8(rt, key),
        Converter<typename Map::mapped_type>::toJS(rt, item)
      );
    }
    return js::Value(std::move(object));
  }
} // namespace expo::modules::v2::cpp::detail

namespace expo::modules::v2::cpp {
  /** A JavaScript object, with its keys as the map's. */
  template<typename Mapped, typename Compare, typename Allocator>
  struct Converter<std::map<std::string, Mapped, Compare, Allocator>> {
    using Map = std::map<std::string, Mapped, Compare, Allocator>;

    static Map fromJS(js::Runtime& rt, const js::Value& value) {
      return detail::mapFromJS<Map>(rt, value);
    }

    static js::Value toJS(js::Runtime& rt, const Map& value) { return detail::mapToJS(rt, value); }
  };

  template<typename Mapped, typename Hash, typename Equal, typename Allocator>
  struct Converter<std::unordered_map<std::string, Mapped, Hash, Equal, Allocator>> {
    using Map = std::unordered_map<std::string, Mapped, Hash, Equal, Allocator>;

    static Map fromJS(js::Runtime& rt, const js::Value& value) {
      return detail::mapFromJS<Map>(rt, value);
    }

    static js::Value toJS(js::Runtime& rt, const Map& value) { return detail::mapToJS(rt, value); }
  };

  /**
   * One of several types. A parameter takes the first alternative the value converts to, in the
   * order they are listed, so `std::variant<int, std::string>` takes `3` as an int and `'3'` as a
   * string. A variant that can hold `std::monostate` is not supported; use `std::optional`.
   */
  template<typename... Ts>
  struct Converter<std::variant<Ts...>> {
    static std::variant<Ts...> fromJS(js::Runtime& rt, const js::Value& value) {
      std::optional<std::variant<Ts...>> result;
      // An integer alternative takes only a whole number on the first pass, so `3.5` reaches the
      // `double` after it and not the `int` before it; when nothing else wants the value, the
      // second pass lets an integer drop the fraction, as it does as a plain parameter.
      const bool matched = (tryAlternative<Ts>(rt, value, result, /* exact */ true) || ...) ||
                           (tryAlternative<Ts>(rt, value, result, /* exact */ false) || ...);
      if (!matched) [[unlikely]] {
        detail::throwConversion(
          std::string("expected ") + names() + ", got " + detail::describe(rt, value)
        );
      }
      return std::move(*result);
    }

    static js::Value toJS(js::Runtime& rt, const std::variant<Ts...>& value) {
      return std::visit(
        [&rt](const auto& alternative) {
          return Converter<std::remove_cvref_t<decltype(alternative)>>::toJS(rt, alternative);
        },
        value
      );
    }

  private:
    template<typename T>
    static bool tryAlternative(
      js::Runtime& rt,
      const js::Value& value,
      std::optional<std::variant<Ts...>>& result,
      const bool exact
    ) {
      if constexpr (std::is_integral_v<T> && !std::is_same_v<T, bool>) {
        if (exact && value.isNumber() && value.getNumber() != std::trunc(value.getNumber())) {
          return false;
        }
      } else {
        (void) exact;
      }
      try {
        result.emplace(std::in_place_type<T>, Converter<T>::fromJS(rt, value));
        return true;
      } catch (const ConversionError&) {
        return false;
      }
    }

    static std::string names() {
      std::string text;
      ((text += (text.empty() ? "" : " or "), text += detail::expectation<Ts>()), ...);
      return text;
    }
  };
} // namespace expo::modules::v2::cpp
