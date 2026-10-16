#pragma once

#include <array>
#include <cstddef>
#include <string>
#include <string_view>
#include <type_traits>

#include <expo-modules-cpp/Converter.h>
#include <expo-modules-cpp/internal/Macros.h>

namespace expo::modules::v2::cpp {
  /** Where `EXPO_ENUM` hangs the description of an enum: found by argument-dependent lookup. */
  template<typename E>
  struct EnumTag {
  };

  namespace detail {
    template<typename E, typename V>
    struct EnumEntry {
      E value;
      V js;
    };

    template<typename E, typename V, size_t N>
    struct EnumInfo {
      using Value = V;

      std::string_view name;
      std::array<EnumEntry<E, V>, N> entries;
    };

    template<typename E>
    constexpr EnumEntry<E, std::string_view> enumEntry(const E value, const char* const js) {
      return {value, std::string_view(js)};
    }

    template<typename E>
    constexpr EnumEntry<E, int> enumEntry(const E value, const int js) {
      return {value, js};
    }

    template<typename E, typename V, size_t N>
    constexpr EnumInfo<E, V, N> makeEnum(const std::string_view name, const EnumEntry<E, V> (&entries)[N]) {
      EnumInfo<E, V, N> info{.name = name, .entries = {}};
      for (size_t i = 0; i < N; i++) {
        info.entries[i] = entries[i];
      }
      return info;
    }

    [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwInvalidEnum(std::string_view name, const std::string& got, const std::string& accepted);

    [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwUnknownEnumValue(std::string_view name);
  } // namespace detail

  /**
   * A C++ enum as a JavaScript string or number: Kotlin's `Enumerable`. An entry crosses by the
   * value it is listed with - a string, or an integer - or by its own name when it is listed alone.
   * Describe the enum with `EXPO_ENUM`, in the namespace the enum is in:
   *
   * ```cpp
   * enum class Style { Light, Heavy };
   * EXPO_ENUM(Style, (Light, "light"), (Heavy, "heavy"))     // JavaScript sees 'light' and 'heavy'
   *
   * enum class Priority { Low = 0, High = 10 };
   * EXPO_ENUM(Priority, (Low, 0), (High, 10))                // JavaScript sees 0 and 10
   *
   * enum class Size { Small, Large };
   * EXPO_ENUM(Size, Small, Large)                            // JavaScript sees 'Small' and 'Large'
   * ```
   */
  template<typename E>
    requires(std::is_enum_v<E> && requires { expoEnumEntries(EnumTag<E>{}); })
  struct Converter<E> {
    static E fromJS(js::Runtime& rt, const js::Value& value) {
      constexpr auto info = expoEnumEntries(EnumTag<E>{});
      using V = typename decltype(info)::Value;

      if constexpr (std::is_same_v<V, int>) {
        if (!value.isNumber()) [[unlikely]] {
          detail::throwExpected(rt, "a number", value);
        }
        const double number = value.getNumber();
        for (const auto& entry: info.entries) {
          if (static_cast<double>(entry.js) == number) {
            return entry.value;
          }
        }

        std::string accepted;
        for (const auto& entry: info.entries) {
          accepted += accepted.empty() ? "" : ", ";
          accepted += std::to_string(entry.js);
        }
        detail::throwInvalidEnum(info.name, "'" + detail::formatNumber(number) + "'", accepted);
      } else {
        if (!value.isString()) [[unlikely]] {
          detail::throwExpected(rt, "a string", value);
        }
        const std::string text = value.getString(rt).utf8(rt);
        for (const auto& entry: info.entries) {
          if (entry.js == text) {
            return entry.value;
          }
        }

        std::string accepted;
        for (const auto& entry: info.entries) {
          accepted += accepted.empty() ? "'" : ", '";
          accepted += entry.js;
          accepted += '\'';
        }
        detail::throwInvalidEnum(info.name, "'" + text + "'", accepted);
      }
    }

    static js::Value toJS(js::Runtime& rt, const E value) {
      constexpr auto info = expoEnumEntries(EnumTag<E>{});
      using V = typename decltype(info)::Value;

      for (const auto& entry: info.entries) {
        if (entry.value == value) {
          if constexpr (std::is_same_v<V, int>) {
            return js::Value(static_cast<double>(entry.js));
          } else {
            return js::Value(js::String::createFromUtf8(rt, std::string(entry.js)));
          }
        }
      }
      detail::throwUnknownEnumValue(info.name);
    }
  };
} // namespace expo::modules::v2::cpp

#define EXPO_DETAIL_ENUM_NAME(E, name) ::expo::modules::v2::cpp::detail::enumEntry(E::name, #name)
#define EXPO_DETAIL_ENUM_PAIR_(E, name, js) ::expo::modules::v2::cpp::detail::enumEntry(E::name, js)
#define EXPO_DETAIL_ENUM_PAIR(E, pair) EXPO_DETAIL_CALL(EXPO_DETAIL_ENUM_PAIR_, (E, EXPO_DETAIL_UNPAREN pair))
#define EXPO_DETAIL_ENUM_ENTRY(E, x) \
  EXPO_DETAIL_IF(EXPO_DETAIL_IS_PAREN(x))(EXPO_DETAIL_ENUM_PAIR, EXPO_DETAIL_ENUM_NAME)(E, x)

/**
 * Describes the enum [Type] for JavaScript. Each entry is `Name`, which JavaScript sees as the
 * string "Name", or `(Name, value)`, which it sees as the string or integer `value`. Use it in the
 * namespace the enum is in, after the enum. Up to 32 entries.
 */
#define EXPO_ENUM(Type, ...) \
  inline constexpr auto expoEnumEntries(::expo::modules::v2::cpp::EnumTag<Type>) { \
    return ::expo::modules::v2::cpp::detail::makeEnum<Type>( \
      #Type, \
      {EXPO_DETAIL_FOR_EACH(EXPO_DETAIL_ENUM_ENTRY, Type, __VA_ARGS__)} \
    ); \
  }
