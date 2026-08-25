#include <expo-modules-v2/converter/encoders/JniEncode.h>

#include <expo-modules-v2/converter/encoders/EncodeCommon.h>
#include <expo-modules-v2/converter/encoders/JniEncoder.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <kolibri/array.h>
#include <kolibri/box.h>
#include <kolibri/class.h>
#include <kolibri/string_utils.h>

namespace expo::modules::v2 {
  jvalue encodeToJniValue(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type
  ) {
    jvalue out{};

    switch (type.kind()) {
      case CppType::BOOLEAN:
        out.z = value.asBool() != 0;
        return out;
      case CppType::INT:
        out.i = static_cast<int32_t>(value.asNumber());
        return out;
      case CppType::LONG:
        out.j = static_cast<int64_t>(value.asNumber());
        return out;
      case CppType::FLOAT:
        out.f = static_cast<float>(value.asNumber());
        return out;
      case CppType::DOUBLE:
        out.d = value.asNumber();
        return out;
      default:
        out.l = encodeToJni(env, rt, value, type);
        return out;
    }
  }

  jobject encodeToJni(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type
  ) {
    const CppType kind = type.kind();

    if (kind == CppType::JS_VALUE) {
      return jsi::JavaScriptValue::create(env, &rt, facebook::jsi::Value(rt, value));
    }

    if (value.isUndefined() || value.isNull()) {
      if (type.nullable()) [[likely]] {
        return nullptr;
      }
      throwNullInNonNullable(rt, type);
    }

    return type.visit<jobject>(
      JniEncoder{
        .env = env,
        .rt = rt,
        .value = value
      }
    );
  }

  jobject encodeToJniDynamic(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value
  ) {
    if (value.isUndefined() || value.isNull()) {
      return nullptr;
    }

    if (value.isBool()) {
      return kolibri::JBoolean::valueOf(env, static_cast<jboolean>(value.getBool())).release();
    }

    if (value.isNumber()) {
      return kolibri::JDouble::valueOf(env, value.getNumber()).release();
    }

    if (value.isString()) {
      // TODO(@lukmccall): optimize
      return kolibri::toJString(env, value.getString(rt).utf8(rt));
    }

    if (value.isObject()) {
      const facebook::jsi::Object object = value.getObject(rt);
      if (object.isArray(rt)) {
        const facebook::jsi::Array array = object.getArray(rt);
        const size_t size = array.size(rt);
        const jobject list = kolibri::JArrayList::constructor.createRaw(env, static_cast<jint>(size));

        for (size_t i = 0; i < size; i++) {
          const jobject element = encodeToJniDynamic(env, rt, array.getValueAtIndex(rt, i));
          kolibri::JArrayList::add(env, list, element);
          env->DeleteLocalRef(element);
        }
        return list;
      }

      if (object.isFunction(rt)) {
        throw facebook::jsi::JSError(rt, "Cannot convert a JS function to a Java value"); // TODO: callbacks
      }

      if (object.isArrayBuffer(rt)) {
        const facebook::jsi::ArrayBuffer arrayBuffer = object.getArrayBuffer(rt);
        return kolibri::JByteArray::create(env, {
          reinterpret_cast<const jbyte*>(arrayBuffer.data(rt)),
          arrayBuffer.size(rt)
        }).release();
      }

      const facebook::jsi::Array names = object.getPropertyNames(rt);
      const size_t size = names.size(rt);
      const jobject map = kolibri::JHashMap::constructor.createRaw(env);
      for (size_t i = 0; i < size; i++) {
        facebook::jsi::String key = names.getValueAtIndex(rt, i).getString(rt);
        const jobject element = encodeToJniDynamic(env, rt, object.getProperty(rt, key));
        kolibri::JHashMap::put(env, map, key.utf8(rt), encodeToJniDynamic(env, rt, object.getProperty(rt, key)));
        env->DeleteLocalRef(element);
      }
      return map;
    }

    throw facebook::jsi::JSError(rt, "Cannot convert this JS value to a Java value");
  }
} // namespace expo::modules::v2
