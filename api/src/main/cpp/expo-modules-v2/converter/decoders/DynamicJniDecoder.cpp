#include <expo-modules-v2/converter/decoders/DynamicJniDecoder.h>

#include <memory>
#include <stdexcept>
#include <vector>

#include <expo-jsi/ByteArrayBuffer.h>

#include <expo-modules-v2/jni/JRecordRegistry.h>
#include <expo-modules-v2/converter/decoders/JniDecode.h>
#include <expo-modules-v2/converter/JsiBuilders.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <kolibri/Ref.h>
#include <kolibri/array.h>
#include <kolibri/box.h>
#include <kolibri/class.h>
#include <kolibri/string_utils.h>

namespace expo::modules::v2 {
  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::INT>() const {
    return facebook::jsi::Value(
      static_cast<double>(kolibri::UnownedRef<kolibri::JInteger>(object)->intValue(env))
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_INT>() const {
    return facebook::jsi::Value(
      static_cast<double>(kolibri::UnownedRef<kolibri::JInteger>(object)->intValue(env))
    );
  }

  // Note: int64 values above 2^53 lose precision; revisit with BigInt support.
  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::LONG>() const {
    return facebook::jsi::Value(
      static_cast<double>(kolibri::UnownedRef<kolibri::JLong>(object)->longValue(env))
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_LONG>() const {
    return facebook::jsi::Value(
      static_cast<double>(kolibri::UnownedRef<kolibri::JLong>(object)->longValue(env))
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::FLOAT>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JFloat>(object)->floatValue(env)
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_FLOAT>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JFloat>(object)->floatValue(env)
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::DOUBLE>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JDouble>(object)->doubleValue(env)
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_DOUBLE>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JDouble>(object)->doubleValue(env)
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOOLEAN>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JBoolean>(object)->booleanValue(env) != 0
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_BOOLEAN>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JBoolean>(object)->booleanValue(env) != 0
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOOLEAN_ARRAY>() const {
    const std::vector<jboolean> values =
      kolibri::UnownedRef<kolibri::JArray<jboolean>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(values[i] != 0);
    });
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::INT_ARRAY>() const {
    const std::vector<jint> values =
      kolibri::UnownedRef<kolibri::JArray<jint>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(static_cast<double>(values[i]));
    });
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::LONG_ARRAY>() const {
    const std::vector<jlong> values =
      kolibri::UnownedRef<kolibri::JArray<jlong>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(static_cast<double>(values[i]));
    });
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::FLOAT_ARRAY>() const {
    const std::vector<jfloat> values =
      kolibri::UnownedRef<kolibri::JArray<jfloat>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(values[i]);
    });
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::DOUBLE_ARRAY>() const {
    const std::vector<jdouble> values =
      kolibri::UnownedRef<kolibri::JArray<jdouble>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(values[i]);
    });
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BYTE_ARRAY>() const {
    const std::vector<jbyte> values =
      kolibri::UnownedRef<kolibri::JArray<jbyte>>(object)->toVector(env);
    return facebook::jsi::ArrayBuffer(
      rt,
      std::make_shared<expo::jsi::ByteArrayBuffer>(
        reinterpret_cast<const uint8_t*>(values.data()),
        values.size()
      )
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::UNIT>() const {
    return facebook::jsi::Value::undefined();
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::STRING>() const {
    return facebook::jsi::String::createFromUtf8(
      rt,
      kolibri::toStdString(env, reinterpret_cast<jstring>(object))
    );
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::LIST>() const {
    const auto list = kolibri::UnownedRef<kolibri::JList>(object);
    const jint size = list->size(env);
    return buildJsArray(rt, static_cast<size_t>(size), [&](size_t i) {
      const kolibri::Ref<> element = list->get(env, static_cast<jint>(i));
      return decodeFromJniDynamic(env, rt, element.get());
    });
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::MAP>() const {
    facebook::jsi::Object result(rt);
    const kolibri::Ref<kolibri::JSet> entrySet =
      kolibri::UnownedRef<kolibri::JMap>(object)->entrySet(env);
    const kolibri::Ref<kolibri::JIterator> iterator = entrySet->iterator(env);
    while (iterator->hasNext(env)) {
      const auto entryRef = iterator->next(env).staticCast<kolibri::JMapEntry>();
      kolibri::Ref<> key = entryRef->getKey(env);
      kolibri::Ref<> val = entryRef->getValue(env);
      result.setProperty(
        rt,
        facebook::jsi::PropNameID::forUtf8(
          rt,
          kolibri::toStdString(env, reinterpret_cast<jstring>(key.get()))
        ),
        decodeFromJniDynamic(env, rt, val.get())
      );
    }
    return result;
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::RECORD>() const {
    const kolibri::Ref<kolibri::JMap> map = JRecordRegistry::dynamicRecordToMap(env, object);
    if (!map) {
      throw std::runtime_error(
        "A record codec was unregistered between classification and decode"
      );
    }
    return decodeFromJniDynamic(env, rt, map.get());
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::JS_VALUE>() const {
    return unwrapJsiHandle<jsi::JavaScriptValue>(env, rt, object);
  }

  template<>
  facebook::jsi::Value DynamicJniDecoder::operator()<CppType::JS_OBJECT>() const {
    return unwrapJsiHandle<jsi::JavaScriptObject>(env, rt, object);
  }
} // namespace expo::modules::v2
