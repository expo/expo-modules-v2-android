#pragma once

#include <string>

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/array.h>

#include <expo-modules-v2/records/RecordRegistry.h>
#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
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
    const facebook::jsi::Array array = value.asObject(rt).asArray(rt);
    const jsize size = static_cast<jsize>(array.size(rt));
    return kolibri::JArray<Element>::createRaw(env, size, [&](const jsize index) -> Element {
      return takeOne(array.getValueAtIndex(rt, static_cast<size_t>(index)));
    });
  }

  [[nodiscard]] ALWAYS_INLINE double numberOf(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
    if (!value.isNumber()) [[unlikely]] {
      throwTypeMismatch(rt, value, "a number");
    }

    return value.getNumber();
  }

  [[nodiscard]] ALWAYS_INLINE bool boolOf(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
    if (!value.isBool()) [[unlikely]] {
      throwTypeMismatch(rt, value, "a boolean");
    }

    return value.getBool();
  }

  /** The JS array a tuple arrives as, checked to hold exactly as many elements as the tuple has. */
  [[nodiscard]] inline facebook::jsi::Array tupleArrayOf(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType::Tuple& tupleType
  ) {
    if (!value.isObject()) [[unlikely]] {
      throwTypeMismatch(rt, value, "an array");
    }
    const facebook::jsi::Object object = value.getObject(rt);
    if (!object.isArray(rt)) [[unlikely]] {
      throwTypeMismatch(rt, value, "an array");
    }

    facebook::jsi::Array array = object.getArray(rt);
    const size_t size = array.size(rt);
    if (size != tupleType.elements.size()) [[unlikely]] {
      throwJSError(rt, {
        "Expected an array of ",
        std::to_string(tupleType.elements.size()),
        " elements, got ",
        std::to_string(size),
      });
    }
    return array;
  }

  [[noreturn]] ALWAYS_INLINE void throwNullInNonNullable(facebook::jsi::Runtime& rt, const ExpectedType& type) {
    throwJSError(rt, {
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
      throwJSError(rt, {
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
