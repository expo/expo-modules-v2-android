#pragma once

#include <string>

#include <jsi/jsi.h>

#include <expo-modules-v2/records/RecordRegistry.h>
#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  [[noreturn]] ALWAYS_INLINE void throwNullInNonNullable(facebook::jsi::Runtime& rt, const ExpectedType& type) {
    throw facebook::jsi::JSError(
      rt,
      std::string("Cannot convert null/undefined into a non-nullable ") +
      type.kotlinType() + " slot"
    );
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
      throw facebook::jsi::JSError(
        rt,
        "Record '" + schema.name + "': field '" + field.name +
        "' is null or missing but declared non-nullable"
      );
    }
    return value;
  }
} // namespace expo::modules::v2
