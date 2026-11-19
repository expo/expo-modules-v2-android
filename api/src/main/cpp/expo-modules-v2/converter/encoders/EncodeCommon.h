#pragma once

#include <string>

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/array.h>

#include <expo-modules-v2/records/RecordRegistry.h>
#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  [[nodiscard]] ALWAYS_INLINE facebook::jsi::Object objectOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value
  ) {
    if (!value.isObject()) [[unlikely]] {
      throwTypeMismatch(value, "an object");
    }

    return value.getObject(rt);
  }

  [[nodiscard]] ALWAYS_INLINE facebook::jsi::Array arrayOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value
  ) {
    if (!value.isObject()) [[unlikely]] {
      throwTypeMismatch(value, "an array");
    }

    const facebook::jsi::Object object = value.getObject(rt);
    if (!object.isArray(rt)) [[unlikely]] {
      throwTypeMismatch(value, "an array");
    }

    return object.getArray(rt);
  }

  [[nodiscard]] ALWAYS_INLINE facebook::jsi::String stringOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value
  ) {
    if (!value.isString()) [[unlikely]] {
      throwTypeMismatch(value, "a string");
    }

    return value.getString(rt);
  }

  /**
   * Encodes a JS array into a new primitive Java array. The elements stream through kolibri's
   * stack chunk (the producer `createRaw`), so no native buffer the size of the array is allocated
   * per call. `takeOne(jsiValue)` converts one JS element into an `Element`.
   */
  template<typename Element, typename TakeOne>
  ALWAYS_INLINE jobject encodeJsArrayToJni(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    TakeOne&& takeOne
  ) {
    const facebook::jsi::Array array = arrayOf(rt, value);
    const jsize size = static_cast<jsize>(array.size(rt));
    return kolibri::JArray<Element>::createRaw(env, size, [&](const jsize index) -> Element {
      return takeOne(array.getValueAtIndex(rt, static_cast<size_t>(index)));
    });
  }

  [[nodiscard]] ALWAYS_INLINE double numberOf(const facebook::jsi::Value& value) {
    if (!value.isNumber()) [[unlikely]] {
      throwTypeMismatch(value, "a number");
    }

    return value.getNumber();
  }

  [[nodiscard]] ALWAYS_INLINE bool boolOf(const facebook::jsi::Value& value) {
    if (!value.isBool()) [[unlikely]] {
      throwTypeMismatch(value, "a boolean");
    }

    return value.getBool();
  }

  [[nodiscard]] inline facebook::jsi::Array tupleArrayOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType::Tuple& tupleType
  ) {
    if (!value.isObject()) [[unlikely]] {
      throwTypeMismatch(value, "an array");
    }

    const facebook::jsi::Object object = value.getObject(rt);
    if (!object.isArray(rt)) [[unlikely]] {
      throwTypeMismatch(value, "an array");
    }

    facebook::jsi::Array array = object.getArray(rt);
    const size_t size = array.size(rt);
    if (size != tupleType.elements.size()) [[unlikely]] {
      throwConversionError(
        {
          "Expected an array of ",
          std::to_string(tupleType.elements.size()),
          " elements, got ",
          std::to_string(size),
        }
      );
    }

    return array;
  }

  [[noreturn]] ALWAYS_INLINE void throwNullInNonNullable(const ExpectedType& type) {
    throwConversionError({
      "Cannot convert null/undefined into a non-nullable ",
      type.kotlinType(),
      " slot",
    });
  }

  [[nodiscard]] ALWAYS_INLINE bool isAbsentOptionalField(
    const RecordFieldSpec& field,
    const facebook::jsi::Value& value
  ) noexcept {
    return field.optional && value.isUndefined();
  }

  // TODO(@lukmccall): remove
  ALWAYS_INLINE facebook::jsi::Value readRecordField(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& object,
    const RecordSchema& schema,
    const RecordFieldSpec& field,
    const facebook::jsi::PropNameID& fieldName
  ) {
    facebook::jsi::Value value = object.getProperty(rt, fieldName);
    if (!field.type.nullable() && field.type.kind() != CppType::JS_VALUE &&
        (value.isUndefined() || value.isNull()) && !isAbsentOptionalField(field, value)) {
      throwConversionError({
        "Record '",
        schema.name,
        "': field '",
        field.name,
        "' is null or missing but declared non-nullable",
      });
    }
    return value;
  }
} // namespace expo::modules::v2
