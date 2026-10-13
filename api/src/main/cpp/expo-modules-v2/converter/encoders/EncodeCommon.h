#pragma once

#include <string>

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/array.h>
#include <kolibri/binary/BinaryBuffer.h>

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

  [[noreturn]] ALWAYS_INLINE void throwNullInNonNullable(facebook::jsi::Runtime& rt, const ExpectedType& type) {
    throwJSError(rt, {
      "Cannot convert null/undefined into a non-nullable ",
      type.kotlinType(),
      " slot",
    });
  }

  /**
   * Writes the presence byte a nullable [type] leads with, and reports whether a payload for
   * [value] follows it. Null in a non-nullable slot throws.
   */
  [[nodiscard]] ALWAYS_INLINE bool writePresence(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type,
    kolibri::binary::BinaryBuffer& out
  ) {
    const bool isNull = value.isUndefined() || value.isNull();
    if (isNull && !type.nullable()) [[unlikely]] {
      throwNullInNonNullable(rt, type);
    }

    if (type.nullable()) {
      out.write<uint8_t>(isNull ? 0 : 1);
    }
    return !isNull;
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
