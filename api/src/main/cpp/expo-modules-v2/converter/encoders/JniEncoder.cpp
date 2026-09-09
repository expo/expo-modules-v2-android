#include <expo-modules-v2/converter/encoders/JniEncoder.h>

#include <span>
#include <string>

#include <expo-modules-v2/converter/encoders/EncodeCommon.h>
#include <expo-modules-v2/converter/encoders/JniEncode.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectState.h>
#include <expo-modules-v2/sharedobjects/SharedObjects.h>
#include <kolibri/Ref.h>
#include <kolibri/array.h>
#include <kolibri/box.h>
#include <kolibri/class.h>
#include <kolibri/string_utils.h>

namespace expo::modules::v2 {
  jobject JniEncoder::operator()(const ExpectedType::List& listType) const {
    const facebook::jsi::Array array = value
      .asObject(rt)
      .asArray(rt);
    const ExpectedType& elementType = *listType.element;
    const size_t size = array.size(rt);
    const jobject result = kolibri::JArrayList::constructor.createRaw(env, static_cast<jint>(size));
    for (size_t i = 0; i < size; i++) {
      const jobject element = encodeToJni(
        env,
        rt,
        array.getValueAtIndex(rt, i),
        elementType
      );
      kolibri::JArrayList::add(env, result, element);
      env->DeleteLocalRef(element);
    }
    return result;
  }

  jobject JniEncoder::operator()(const ExpectedType::Map& mapType) const {
    const facebook::jsi::Object object = value.asObject(rt);
    const ExpectedType& valueType = *mapType.value;
    const facebook::jsi::Array names = object.getPropertyNames(rt);
    const size_t size = names.size(rt);
    const jobject map = kolibri::JHashMap::constructor.createRaw(env);
    for (size_t i = 0; i < size; i++) {
      // TODO(@lukmccall): optimize key
      facebook::jsi::String key = names.getValueAtIndex(rt, i).getString(rt);
      const jobject element = encodeToJni(
        env,
        rt,
        object.getProperty(rt, key),
        valueType
      );
      kolibri::JHashMap::put(env, map, key.utf8(rt), element);
      env->DeleteLocalRef(element);
    }
    return map;
  }

  jobject JniEncoder::operator()(const ExpectedType::SharedObject& sharedType) const {
    using sharedobjects::SharedObjectClassRegistry;

    if (!value.isObject()) {
      throw facebook::jsi::JSError(
        rt,
        "Expected " + SharedObjectClassRegistry::nameOf(sharedType.classId) +
        ", got a value that is not an object"
      );
    }

    const facebook::jsi::Object object = value.getObject(rt);
    const auto* state = sharedobjects::SharedObjects::stateOf(rt, object);
    if (state == nullptr) {
      throw facebook::jsi::JSError(
        rt,
        "Expected " + SharedObjectClassRegistry::nameOf(sharedType.classId) +
        ", got an object that is not a shared object"
      );
    }

    if (state->released()) {
      throw facebook::jsi::JSError(
        rt,
        "Cannot pass a released " + state->spec().name + " to a native function"
      );
    }

    if (state->spec().classId != sharedType.classId) {
      const jclass declaredClass = SharedObjectClassRegistry::javaClassOf(sharedType.classId);
      // TODO(@lukmccall): move class check to kotlin
      if (declaredClass == nullptr || !env->IsInstanceOf(state->instance(), declaredClass)) {
        throw facebook::jsi::JSError(
          rt,
          "Expected " + SharedObjectClassRegistry::nameOf(sharedType.classId) +
          ", got " + state->spec().name
        );
      }
    }

    return env->NewLocalRef(state->instance());
  }

  jobject JniEncoder::operator()(const ExpectedType::Record& recordType) const {
    RecordPropertyCache& recordProperties = RecordPropertyCache::get(rt);
    const RecordAccessPlan& plan = recordProperties.planFor(rt, recordType.schemaId);
    const RecordSchema& schema = *plan.schema;
    const facebook::jsi::Object object = value.asObject(rt);
    const jobject map = kolibri::JHashMap::constructor.createRaw(env);
    for (size_t i = 0; i < schema.fields.size(); i++) {
      const RecordFieldSpec& field = schema.fields[i];
      const facebook::jsi::Value fieldValue = readRecordField(
        rt,
        object,
        schema,
        field,
        plan.fieldNames[i]
      );

      // Handle optional fields
      if (isAbsentOptionalField(field, fieldValue)) [[unlikely]] {
        continue;
      }

      const jobject element = encodeToJni(env, rt, fieldValue, field.type);
      // TODO(@lukmccall): optimize
      kolibri::JHashMap::put(env, map, field.name, element);
      env->DeleteLocalRef(element);
    }
    return map;
  }

  template<>
  jobject JniEncoder::operator()<CppType::INT>() const {
    return kolibri::JInteger::valueOf(env, static_cast<jint>(value.asNumber())).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::BOX_INT>() const {
    return kolibri::JInteger::valueOf(env, static_cast<jint>(value.asNumber())).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::LONG>() const {
    return kolibri::JLong::valueOf(env, static_cast<jlong>(value.asNumber())).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::BOX_LONG>() const {
    return kolibri::JLong::valueOf(env, static_cast<jlong>(value.asNumber())).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::FLOAT>() const {
    return kolibri::JFloat::valueOf(env, static_cast<jfloat>(value.asNumber())).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::BOX_FLOAT>() const {
    return kolibri::JFloat::valueOf(env, static_cast<jfloat>(value.asNumber())).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::DOUBLE>() const {
    return kolibri::JDouble::valueOf(env, value.asNumber()).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::BOX_DOUBLE>() const {
    return kolibri::JDouble::valueOf(env, value.asNumber()).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::BOOLEAN>() const {
    return kolibri::JBoolean::valueOf(env, static_cast<jboolean>(value.asBool())).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::BOX_BOOLEAN>() const {
    return kolibri::JBoolean::valueOf(env, static_cast<jboolean>(value.asBool())).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::UNIT>() const {
    return kolibri::JUnit::instance(env).release();
  }

  template<>
  jobject JniEncoder::operator()<CppType::STRING>() const {
    // TODO(@lukmccall): optimize
    return kolibri::toJString(env, value.asString(rt).utf8(rt));
  }

  template<>
  jobject JniEncoder::operator()<CppType::DOUBLE_ARRAY>() const {
    return encodeJsArrayToJni<jdouble>(env, rt, value, [](const facebook::jsi::Value& value) {
      return value.asNumber();
    });
  }

  template<>
  jobject JniEncoder::operator()<CppType::INT_ARRAY>() const {
    return encodeJsArrayToJni<jint>(env, rt, value, [](const facebook::jsi::Value& value) {
      return static_cast<jint>(value.asNumber());
    });
  }

  template<>
  jobject JniEncoder::operator()<CppType::LONG_ARRAY>() const {
    return encodeJsArrayToJni<jlong>(env, rt, value, [](const facebook::jsi::Value& value) {
      return static_cast<jlong>(value.asNumber());
    });
  }

  template<>
  jobject JniEncoder::operator()<CppType::FLOAT_ARRAY>() const {
    return encodeJsArrayToJni<jfloat>(env, rt, value, [](const facebook::jsi::Value& value) {
      return static_cast<jfloat>(value.asNumber());
    });
  }

  template<>
  jobject JniEncoder::operator()<CppType::BOOLEAN_ARRAY>() const {
    return encodeJsArrayToJni<jboolean>(env, rt, value, [](const facebook::jsi::Value& value) {
      return static_cast<jboolean>(value.asBool());
    });
  }

  template<>
  jobject JniEncoder::operator()<CppType::BYTE_ARRAY>() const {
    const facebook::jsi::ArrayBuffer arrayBuffer = value.asObject(rt).getArrayBuffer(rt);
    return kolibri::JByteArray::createRaw(env, {
      reinterpret_cast<const jbyte*>(arrayBuffer.data(rt)),
      arrayBuffer.size(rt)
    });
  }

  template<>
  jobject JniEncoder::operator()<CppType::JS_OBJECT>() const {
    return jsi::JavaScriptObject::create(env, &rt, value.asObject(rt));
  }

  template<>
  jobject JniEncoder::operator()<CppType::ANY>() const {
    return encodeToJniDynamic(env, rt, value);
  }
} // namespace expo::modules::v2
