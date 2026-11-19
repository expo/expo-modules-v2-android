#include <expo-modules-v2/unions/UnionDispatch.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <string>

#include <kolibri/env.h>

#include <expo-modules-v2/converter/RecordPropertyCache.h>
#include <expo-modules-v2/objects/InstanceRef.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectState.h>
#include <expo-modules-v2/sharedobjects/SharedObjects.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  namespace {
    std::string describeVariant(const UnionVariantSpec& variant) {
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

    /** Whether a value of [type] can be [value], by its kind of JavaScript value alone. */
    bool kindMightTake(const ExpectedType& type, const facebook::jsi::Value& value) {
      switch (type.kind()) {
        case CppType::BOOLEAN:
          return value.isBool();
        case CppType::INT:
        case CppType::LONG:
        case CppType::FLOAT:
        case CppType::DOUBLE:
          return value.isNumber();
        case CppType::STRING:
          return value.isString();
        case CppType::ANY:
        case CppType::JS_VALUE:
        case CppType::UNIT:
        case CppType::UNION:
          return true;
        default:
          // Lists, maps, records, typed arrays and the other objects.
          return value.isObject();
      }
    }

    /** Whether [value] - an element or a map value - fits [type], null included. */
    bool elementMightTake(const ExpectedType& type, const facebook::jsi::Value& value) {
      if (value.isUndefined() || value.isNull()) {
        return type.nullable() || type.kind() == CppType::JS_VALUE || type.kind() == CppType::ANY;
      }
      return kindMightTake(type, value);
    }

    bool recordMightTake(
      facebook::jsi::Runtime& rt,
      const ExpectedType& recordType,
      const facebook::jsi::Object& object
    ) {
      const RecordAccessPlan& plan = RecordPropertyCache::get(rt).planFor(rt, recordType.recordSchemaId());
      const RecordSchema& schema = *plan.schema;
      for (size_t i = 0; i < schema.fields.size(); i++) {
        const RecordFieldSpec& field = schema.fields[i];
        const facebook::jsi::Value fieldValue = object.getProperty(rt, plan.fieldNames[i]);
        if (fieldValue.isUndefined() || fieldValue.isNull()) {
          // Like the conversion: a field may be absent when it is optional, nullable or any value.
          const bool absentOptional = field.optional && fieldValue.isUndefined();
          if (!absentOptional && !field.type.nullable() && field.type.kind() != CppType::JS_VALUE) {
            return false;
          }
          continue;
        }
        if (!kindMightTake(field.type, fieldValue)) {
          return false;
        }
      }
      return true;
    }

    bool listMightTake(
      facebook::jsi::Runtime& rt,
      const ExpectedType& listType,
      const facebook::jsi::Value& value
    ) {
      const facebook::jsi::Array array = value.getObject(rt).getArray(rt);
      if (array.size(rt) == 0) {
        return true;
      }
      const facebook::jsi::Value first = array.getValueAtIndex(rt, 0);

      switch (listType.kind()) {
        case CppType::LIST:
          return elementMightTake(listType.listElement(), first);
        case CppType::INT_ARRAY:
        case CppType::LONG_ARRAY:
        case CppType::FLOAT_ARRAY:
        case CppType::DOUBLE_ARRAY:
          return first.isNumber();
        case CppType::BOOLEAN_ARRAY:
          return first.isBool();
        default:
          return true;
      }
    }

    bool mapMightTake(
      facebook::jsi::Runtime& rt,
      const ExpectedType& mapType,
      const facebook::jsi::Object& object
    ) {
      const facebook::jsi::Array names = object.getPropertyNames(rt);
      if (names.size(rt) == 0) {
        return true;
      }
      const facebook::jsi::String first = names.getValueAtIndex(rt, 0).getString(rt);
      const facebook::jsi::Value value = object.getProperty(rt, facebook::jsi::PropNameID::forString(rt, first));
      return elementMightTake(mapType.mapValue(), value);
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

    /** Whether [object] is a shared object of the class [classId] names, or of a subclass of it. */
    bool isSharedObjectOf(facebook::jsi::Runtime& rt, const facebook::jsi::Object& object, const int classId) {
      const auto* state = sharedobjects::SharedObjects::stateOf(rt, object);
      if (state == nullptr) {
        return false;
      }
      if (classId == state->spec().classId) {
        return true;
      }
      const jclass declared = sharedobjects::SharedObjectClassRegistry::javaClassOf(classId);
      if (declared == nullptr) {
        return false;
      }
      const objects::ScopedInstance instance(state->instanceRef());
      return instance.get() != nullptr && kolibri::getEnv()->IsInstanceOf(instance.get(), declared);
    }
  } // namespace

  bool unionVariantMightConvert(
    facebook::jsi::Runtime& rt,
    const UnionVariantSpec& variant,
    const facebook::jsi::Value& value
  ) {
    switch (variant.kind) {
      case UnionVariantKind::ARRAY:
        return listMightTake(rt, variant.type, value);
      case UnionVariantKind::OBJECT: {
        const facebook::jsi::Object object = value.getObject(rt);
        return variant.type.kind() == CppType::RECORD
          ? recordMightTake(rt, variant.type, object)
          : mapMightTake(rt, variant.type, object);
      }
      case UnionVariantKind::BINARY:
        return isBinary(rt, value.getObject(rt));
      case UnionVariantKind::SHARED_OBJECT:
        return isSharedObjectOf(rt, value.getObject(rt), variant.type.sharedClassId());
      default:
        return true;
    }
  }

  bool unionVariantMightTake(
    facebook::jsi::Runtime& rt,
    const UnionVariantKind kind,
    const facebook::jsi::Value& value
  ) {
    switch (kind) {
      case UnionVariantKind::BOOLEAN:
        return value.isBool();
      case UnionVariantKind::NUMBER:
        return value.isNumber();
      case UnionVariantKind::STRING:
      case UnionVariantKind::LITERAL:
        return value.isString();
      case UnionVariantKind::ARRAY:
        return value.isObject() && value.getObject(rt).isArray(rt);
      case UnionVariantKind::OBJECT: {
        if (!value.isObject()) {
          return false;
        }
        const facebook::jsi::Object object = value.getObject(rt);
        return !object.isArray(rt) && !object.isFunction(rt);
      }
      case UnionVariantKind::BINARY:
      case UnionVariantKind::SHARED_OBJECT:
      case UnionVariantKind::ANY_OBJECT:
        return value.isObject();
      case UnionVariantKind::ANY:
        return true;
    }
    return true;
  }

  bool unionNumberFits(const ExpectedType& type, const double number) noexcept {
    switch (type.kind()) {
      case CppType::INT:
        return std::trunc(number) == number &&
          number >= static_cast<double>(std::numeric_limits<int32_t>::min()) &&
          number <= static_cast<double>(std::numeric_limits<int32_t>::max());
      case CppType::LONG:
        // 2^63 is the first double past int64's range.
        return std::trunc(number) == number && std::fabs(number) < 9223372036854775808.0;
      default:
        return true;
    }
  }

  COLD NOINLINE void throwNoUnionVariant(
    const UnionSchema& schema,
    const facebook::jsi::Value& value
  ) {
    std::string expected = schema.name + " (";
    for (size_t i = 0; i < schema.variants.size(); i++) {
      if (i > 0) {
        expected += " | ";
      }
      expected += describeVariant(schema.variants[i]);
    }
    expected += ")";
    throwTypeMismatch(value, expected);
  }

  const UnionVariantSpec& unionVariantAt(
    const UnionSchema& schema,
    const int index
  ) {
    if (index < 0 || static_cast<size_t>(index) >= schema.variants.size()) [[unlikely]] {
      throwConversionError({schema.name, " has no variant #", std::to_string(index)});
    }
    return schema.variants[static_cast<size_t>(index)];
  }
} // namespace expo::modules::v2
