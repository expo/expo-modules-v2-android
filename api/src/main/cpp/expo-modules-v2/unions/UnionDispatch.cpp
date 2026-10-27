#include <expo-modules-v2/unions/UnionDispatch.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <vector>

#include <kolibri/env.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <expo-modules-v2/records/RecordRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectState.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>
#include <expo-modules-v2/sharedobjects/SharedObjects.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  namespace {
    constexpr int kNone = -1;

    const std::vector<int>& variantsOfKind(const UnionSchema& schema, const UnionVariantKind kind) noexcept {
      return schema.byKind[static_cast<size_t>(kind)];
    }

    int firstOfKind(const UnionSchema& schema, const UnionVariantKind kind) noexcept {
      const std::vector<int>& variants = variantsOfKind(schema, kind);
      return variants.empty() ? kNone : variants.front();
    }

    std::string describeVariant(const UnionSchema& schema, const UnionVariantSpec& variant) {
      switch (variant.kind) {
        case UnionVariantKind::BOOLEAN:
          return "boolean";
        case UnionVariantKind::NUMBER:
          return "number";
        case UnionVariantKind::STRING:
          return "string";
        case UnionVariantKind::ARRAY:
          return "array";
        case UnionVariantKind::BINARY:
          return "ArrayBuffer";
        case UnionVariantKind::OBJECT:
          if (schema.discriminator) {
            return "{ " + *schema.discriminator + ": '" + variant.tag + "' }";
          }
          return variant.type.kind() == CppType::RECORD ? variant.type.kotlinType() : "object";
        case UnionVariantKind::SHARED_OBJECT:
          return variant.type.kotlinType();
        case UnionVariantKind::ANY_OBJECT:
          return "object";
        case UnionVariantKind::ANY:
          return "any";
        case UnionVariantKind::LITERAL:
          return "'" + variant.tag + "'";
      }
      return "?";
    }

    [[noreturn]] COLD NOINLINE void throwNoVariant(
      facebook::jsi::Runtime& rt,
      const UnionSchema& schema,
      const facebook::jsi::Value& value
    ) {
      std::string expected = schema.name + " (";
      for (size_t i = 0; i < schema.variants.size(); i++) {
        if (i > 0) {
          expected += " | ";
        }
        expected += describeVariant(schema, schema.variants[i]);
      }
      expected += ")";
      throwTypeMismatch(rt, value, expected);
    }

    [[noreturn]] COLD NOINLINE void throwUnknownTag(
      facebook::jsi::Runtime& rt,
      const UnionSchema& schema,
      const facebook::jsi::Value& tag
    ) {
      std::string expected = "'" + *schema.discriminator + "' of " + schema.name + " to be ";
      bool first = true;
      for (const UnionVariantSpec& variant : schema.variants) {
        if (variant.kind != UnionVariantKind::OBJECT) {
          continue;
        }
        if (!first) {
          expected += " | ";
        }
        first = false;
        expected += "'" + variant.tag + "'";
      }

      if (tag.isString()) {
        throwJSError(rt, {"Expected ", expected, ", got '", tag.getString(rt).utf8(rt), "'"});
      }
      throwTypeMismatch(rt, tag, expected);
    }

    /** Whether a number variant of [type] takes [number]: an integer type only whole numbers it holds. */
    bool takesNumber(const ExpectedType& type, const double number) noexcept {
      switch (type.kind()) {
        case CppType::INT:
        case CppType::BOX_INT:
          return std::trunc(number) == number &&
            number >= static_cast<double>(std::numeric_limits<int32_t>::min()) &&
            number <= static_cast<double>(std::numeric_limits<int32_t>::max());
        case CppType::LONG:
        case CppType::BOX_LONG:
          // 2^63 is the first double past int64's range.
          return std::trunc(number) == number && std::fabs(number) < 9223372036854775808.0;
        default:
          return true;
      }
    }

    /** Whether an object variant of [type] takes [object]: a record only one with every field it needs. */
    bool takesObject(
      facebook::jsi::Runtime& rt,
      const ExpectedType& type,
      const facebook::jsi::Object& object
    ) {
      if (type.kind() != CppType::RECORD) {
        return true;
      }

      const RecordSchema& record = RecordRegistry::get(type.recordSchemaId());
      for (const RecordFieldSpec& field : record.fields) {
        if (field.optional || field.type.nullable() || field.type.kind() == CppType::JS_VALUE) {
          continue;
        }
        const facebook::jsi::Value value = object.getProperty(rt, field.name.c_str());
        if (value.isUndefined() || value.isNull()) {
          return false;
        }
      }
      return true;
    }

    /**
     * The first of [candidates] that [takes]. The last one takes whatever it is offered, so a union
     * with one variant of a kind converts like a plain value of that type, errors included.
     */
    template<typename Takes>
    int firstTaking(const std::vector<int>& candidates, Takes&& takes) {
      for (size_t i = 0; i < candidates.size(); i++) {
        if (i + 1 == candidates.size() || takes(candidates[i])) {
          return candidates[i];
        }
      }
      return kNone;
    }

    int selectString(
      facebook::jsi::Runtime& rt,
      const UnionSchema& schema,
      const facebook::jsi::Value& value
    ) {
      std::optional<std::string> string;
      for (const int index : variantsOfKind(schema, UnionVariantKind::STRING)) {
        const UnionVariantSpec& variant = schema.variants[static_cast<size_t>(index)];
        if (variant.kind != UnionVariantKind::LITERAL) {
          return index;
        }
        if (!string) {
          string = value.getString(rt).utf8(rt);
        }
        if (variant.tag == *string) {
          return index;
        }
      }
      return kNone;
    }

    int selectSharedObject(
      facebook::jsi::Runtime& rt,
      const UnionSchema& schema,
      const facebook::jsi::Object& object
    ) {
      const std::vector<int>& candidates = variantsOfKind(schema, UnionVariantKind::SHARED_OBJECT);
      if (candidates.empty()) {
        return kNone;
      }

      const auto* state = sharedobjects::SharedObjects::stateOf(rt, object);
      if (state == nullptr) {
        return kNone;
      }

      // A variant takes its class and every subclass, so a subclass is declared first.
      JNIEnv* env = kolibri::getEnv();
      for (const int index : candidates) {
        const int classId = schema.variants[static_cast<size_t>(index)].type.sharedClassId();
        if (classId == state->spec().classId) {
          return index;
        }
        const jclass declared = sharedobjects::SharedObjectClassRegistry::javaClassOf(classId);
        if (declared != nullptr && env->IsInstanceOf(state->instance(), declared)) {
          return index;
        }
      }
      return kNone;
    }

    bool isBinary(facebook::jsi::Runtime& rt, const facebook::jsi::Object& object) {
      if (object.isArrayBuffer(rt)) {
        return true;
      }

      return rt.global()
        .getPropertyAsObject(rt, "ArrayBuffer")
        .getPropertyAsFunction(rt, "isView")
        .call(rt, object)
        .getBool();
    }

    /** The variant a tagged object names, or kNone when the union has no discriminator. */
    int selectTagged(
      facebook::jsi::Runtime& rt,
      const UnionSchema& schema,
      const facebook::jsi::Object& object
    ) {
      if (!schema.discriminator) {
        return kNone;
      }

      const std::vector<int>& candidates = variantsOfKind(schema, UnionVariantKind::OBJECT);
      const facebook::jsi::Value tag = object.getProperty(rt, schema.discriminator->c_str());
      if (tag.isString()) {
        const std::string string = tag.getString(rt).utf8(rt);
        for (const int index : candidates) {
          if (schema.variants[static_cast<size_t>(index)].tag == string) {
            return index;
          }
        }
      }

      const bool hasFallback =
        firstOfKind(schema, UnionVariantKind::ANY_OBJECT) != kNone ||
        firstOfKind(schema, UnionVariantKind::ANY) != kNone;
      if (!candidates.empty() && !hasFallback) {
        throwUnknownTag(rt, schema, tag);
      }
      return kNone;
    }

    int selectObject(
      facebook::jsi::Runtime& rt,
      const UnionSchema& schema,
      const facebook::jsi::Object& object
    ) {
      if (object.isArray(rt)) {
        return firstOfKind(schema, UnionVariantKind::ARRAY);
      }

      if (object.isFunction(rt)) {
        return kNone;
      }

      if (const int shared = selectSharedObject(rt, schema, object); shared != kNone) {
        return shared;
      }

      if (firstOfKind(schema, UnionVariantKind::BINARY) != kNone && isBinary(rt, object)) {
        return firstOfKind(schema, UnionVariantKind::BINARY);
      }

      if (schema.discriminator) {
        return selectTagged(rt, schema, object);
      }

      return firstTaking(variantsOfKind(schema, UnionVariantKind::OBJECT), [&](const int index) {
        return takesObject(rt, schema.variants[static_cast<size_t>(index)].type, object);
      });
    }

    int selectByKind(
      facebook::jsi::Runtime& rt,
      const UnionSchema& schema,
      const facebook::jsi::Value& value
    ) {
      if (value.isBool()) {
        return firstOfKind(schema, UnionVariantKind::BOOLEAN);
      }

      if (value.isNumber()) {
        const double number = value.getNumber();
        return firstTaking(variantsOfKind(schema, UnionVariantKind::NUMBER), [&](const int index) {
          return takesNumber(schema.variants[static_cast<size_t>(index)].type, number);
        });
      }

      if (value.isString()) {
        return selectString(rt, schema, value);
      }

      if (value.isObject()) {
        const facebook::jsi::Object object = value.getObject(rt);
        const int index = selectObject(rt, schema, object);
        return index != kNone ? index : firstOfKind(schema, UnionVariantKind::ANY_OBJECT);
      }

      return kNone;
    }
  } // namespace

  size_t selectUnionVariant(
    facebook::jsi::Runtime& rt,
    const UnionSchema& schema,
    const facebook::jsi::Value& value
  ) {
    int index = selectByKind(rt, schema, value);
    if (index == kNone) {
      index = firstOfKind(schema, UnionVariantKind::ANY);
    }
    if (index == kNone) [[unlikely]] {
      throwNoVariant(rt, schema, value);
    }
    return static_cast<size_t>(index);
  }

  facebook::jsi::Value finishUnionValue(
    facebook::jsi::Runtime& rt,
    const UnionSchema& schema,
    const UnionVariantSpec& variant,
    facebook::jsi::Value payload
  ) {
    if (variant.kind == UnionVariantKind::LITERAL) {
      return facebook::jsi::String::createFromUtf8(rt, variant.tag);
    }

    if (schema.discriminator && variant.kind == UnionVariantKind::OBJECT && payload.isObject()) {
      payload.getObject(rt).setProperty(
        rt,
        schema.discriminator->c_str(),
        facebook::jsi::String::createFromUtf8(rt, variant.tag)
      );
    }
    return payload;
  }

  const UnionVariantSpec& unionVariantAt(
    facebook::jsi::Runtime& rt,
    const UnionSchema& schema,
    const int index
  ) {
    if (index < 0 || static_cast<size_t>(index) >= schema.variants.size()) [[unlikely]] {
      throwJSError(rt, {schema.name, " has no variant #", std::to_string(index)});
    }
    return schema.variants[static_cast<size_t>(index)];
  }
} // namespace expo::modules::v2
