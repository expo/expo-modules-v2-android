#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <utility>
#include <vector>

#include <expo-modules-cpp/Converter.h>
#include <expo-modules-cpp/internal/Macros.h>
#include <expo-modules-cpp/internal/RuntimeData.h>

namespace expo::modules::v2::cpp {
  /** Where `EXPO_RECORD` hangs the description of a struct: found by argument-dependent lookup. */
  template<typename T>
  struct RecordTag {
  };

  /** One field of a record: its JavaScript name, and the member it is stored in. */
  template<typename T, typename V>
  struct Field {
    std::string_view name;
    V T::* member;
    bool isOptional = false;

    /**
     * The field may be left out of the JavaScript object, and then keeps the value the struct
     * starts with - Kotlin's constructor default. Without it a missing field is an error, unless the
     * field is a `std::optional`.
     */
    [[nodiscard]] constexpr Field optional() const {
      Field copy = *this;
      copy.isOptional = true;
      return copy;
    }
  };

  template<typename T, typename V>
  constexpr Field<T, V> field(const std::string_view name, V T::* const member) {
    return {.name = name, .member = member};
  }

  namespace detail {
    template<typename T, typename... Fields>
    struct RecordInfo {
      std::string_view name;
      std::tuple<Fields...> fields;
    };

    template<typename T, typename... Fields>
    constexpr RecordInfo<T, Fields...> makeRecord(const std::string_view name, Fields... fields) {
      return {.name = name, .fields = std::tuple<Fields...>(fields...)};
    }

    /** A record's property names as JavaScript interns them: made once per runtime, not per call. */
    template<typename T>
    struct RecordNames {
      std::vector<js::PropNameID> names;

      explicit RecordNames(js::Runtime& rt) {
        constexpr auto info = expoRecordFields(RecordTag<T>{});
        std::apply(
          [&](const auto&... fields) { (names.push_back(js::PropNameID::forUtf8(rt, std::string(fields.name))), ...); },
          info.fields
        );
      }
    };

    [[noreturn]] EXPO_COLD EXPO_NOINLINE void throwMissingField(std::string_view record, std::string_view field);

    [[noreturn]] EXPO_COLD EXPO_NOINLINE void rethrowFieldError(
      std::string_view record,
      std::string_view field,
      const ConversionError& error
    );
  } // namespace detail

  /**
   * A struct as a JavaScript object: Kotlin's `Record`. Its fields are listed with `EXPO_RECORD`, in
   * the namespace the struct is in, and cross by name:
   *
   * ```cpp
   * struct Options {
   *   std::string title;
   *   int retries = 3;
   *   std::optional<std::string> subtitle;
   * };
   * EXPO_RECORD(Options, title, (retries, optional), subtitle)
   * ```
   *
   * The struct has to be default-constructible; it starts from that default, and a field that is
   * left out of the JavaScript object and marked `optional` keeps it. A field that is a
   * `std::optional` may always be left out or `null`. Any other field that is missing is an error.
   */
  template<typename T>
    requires(std::is_class_v<T> && requires { expoRecordFields(RecordTag<T>{}); })
  struct Converter<T> {
    static_assert(std::is_default_constructible_v<T>, "A record has to be default-constructible");

    static T fromJS(js::Runtime& rt, const js::Value& value) {
      constexpr auto info = expoRecordFields(RecordTag<T>{});
      const js::Object object = detail::expectObject(rt, value);

      T result{};
      size_t index = 0;
      std::apply([&](const auto&... fields) { (readField(rt, object, info.name, fields, index++, result), ...); }, info.fields);
      return result;
    }

    static js::Value toJS(js::Runtime& rt, const T& value) {
      constexpr auto info = expoRecordFields(RecordTag<T>{});
      js::Object object(rt);

      size_t index = 0;
      std::apply(
        [&](const auto&... fields) {
          // The value first: converting a nested record names its own fields in the meantime, and
          // this field's name is taken once that is done.
          (putField(rt, object, index++, fields.name, writeField(rt, value, fields)), ...);
        },
        info.fields
      );
      return js::Value(std::move(object));
    }

  private:
    /**
     * The property name of the [index]th field: the runtime's cached one, or a fresh one outside an
     * attached runtime. A fresh name belongs to whoever asked for it, never to a shared slot - a
     * nested record asks for its own in the middle of its parent's, and a name that outlived its
     * runtime would be released after it.
     */
    class FieldName {
    public:
      FieldName(js::Runtime& rt, const size_t index, const std::string_view fallback) {
        if (detail::RuntimeData* const data = detail::RuntimeData::find(rt)) {
          cached_ = &data->local<detail::RecordNames<T>>().names[index];
        } else {
          own_.emplace(js::PropNameID::forUtf8(rt, std::string(fallback)));
        }
      }

      [[nodiscard]] const js::PropNameID& get() const noexcept { return cached_ != nullptr ? *cached_ : *own_; }

    private:
      const js::PropNameID* cached_ = nullptr;
      std::optional<js::PropNameID> own_;
    };

    static void putField(js::Runtime& rt, js::Object& object, const size_t index, const std::string_view name, js::Value&& value) {
      object.setProperty(rt, FieldName(rt, index, name).get(), std::move(value));
    }

    template<typename V>
    static js::Value writeField(js::Runtime& rt, const T& value, const Field<T, V>& field) {
      return Converter<V>::toJS(rt, value.*field.member);
    }

    template<typename V>
    static void readField(
      js::Runtime& rt,
      const js::Object& object,
      const std::string_view record,
      const Field<T, V>& field,
      const size_t index,
      T& result
    ) {
      const js::Value fieldValue = object.getProperty(rt, FieldName(rt, index, field.name).get());

      const bool absent = fieldValue.isUndefined();
      if (absent && field.isOptional) {
        return;
      }
      if ((absent || fieldValue.isNull()) && !detail::acceptsMissing<V>()) [[unlikely]] {
        detail::throwMissingField(record, field.name);
      }

      try {
        result.*field.member = Converter<V>::fromJS(rt, fieldValue);
      } catch (const ConversionError& error) {
        detail::rethrowFieldError(record, field.name, error);
      }
    }
  };
} // namespace expo::modules::v2::cpp

#define EXPO_DETAIL_FIELD_NAME(Type, name) ::expo::modules::v2::cpp::field(#name, &Type::name)
#define EXPO_DETAIL_FIELD_PAIR_(Type, name, flag) ::expo::modules::v2::cpp::field(#name, &Type::name).flag()
#define EXPO_DETAIL_FIELD_PAIR(Type, pair) EXPO_DETAIL_CALL(EXPO_DETAIL_FIELD_PAIR_, (Type, EXPO_DETAIL_UNPAREN pair))
#define EXPO_DETAIL_FIELD(Type, x) \
  EXPO_DETAIL_IF(EXPO_DETAIL_IS_PAREN(x))(EXPO_DETAIL_FIELD_PAIR, EXPO_DETAIL_FIELD_NAME)(Type, x)

/**
 * Describes the struct [Type] for JavaScript. Each field is `name`, or `(name, optional)` for one
 * that may be left out. Use it in the namespace the struct is in, after the struct. Up to 32 fields.
 */
#define EXPO_RECORD(Type, ...) \
  inline constexpr auto expoRecordFields(::expo::modules::v2::cpp::RecordTag<Type>) { \
    return ::expo::modules::v2::cpp::detail::makeRecord<Type>( \
      #Type, \
      EXPO_DETAIL_FOR_EACH(EXPO_DETAIL_FIELD, Type, __VA_ARGS__) \
    ); \
  }
