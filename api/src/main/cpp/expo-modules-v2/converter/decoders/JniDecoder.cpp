#include <expo-modules-v2/converter/decoders/JniDecoder.h>

#include <memory>
#include <vector>

#include <expo-jsi/ByteArrayBuffer.h>

#include <expo-modules-v2/converter/JsiStringCodec.h>
#include <expo-modules-v2/converter/decoders/JniDecode.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>
#include <expo-modules-v2/converter/JsiBuilders.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <kolibri/Ref.h>
#include <kolibri/array.h>
#include <kolibri/box.h>
#include <kolibri/class.h>
#include <kolibri/string_utils.h>

namespace expo::modules::v2 {
  facebook::jsi::Value JniDecoder::operator()(const ExpectedType::List& listType) const {
    const ExpectedType& elementType = *listType.element;
    const auto list = kolibri::UnownedRef<kolibri::JList>(object);
    const auto size = static_cast<size_t>(list->size(env));
    return buildJsArray(rt, size, [&](const size_t i) {
      const kolibri::Ref<> element = list->get(env, static_cast<jint>(i));
      return decodeFromJni(env, rt, element.get(), elementType);
    });
  }

  facebook::jsi::Value JniDecoder::operator()(const ExpectedType::Map& mapType) const {
    const ExpectedType& valueType = *mapType.value;
    facebook::jsi::Object result(rt);
    const kolibri::Ref<kolibri::JSet> entrySet =
      kolibri::UnownedRef<kolibri::JMap>(object)->entrySet(env);
    const kolibri::Ref<kolibri::JIterator> iterator = entrySet->iterator(env);

    while (iterator->hasNext(env)) {
      const kolibri::Ref<kolibri::JMapEntry> entryRef = iterator
        ->next(env)
        .staticCast<kolibri::JMapEntry>();

      kolibri::Ref<> key = entryRef->getKey(env);
      kolibri::Ref<> value = entryRef->getValue(env);

      result.setProperty(
        rt,
        facebook::jsi::PropNameID::forUtf8(
          rt,
          kolibri::toStdString(env, reinterpret_cast<jstring>(key.get()))
        ),
        decodeFromJni(env, rt, value.get(), valueType)
      );
    }

    return result;
  }

  facebook::jsi::Value JniDecoder::operator()(const ExpectedType::Record& recordType) const {
    RecordPropertyCache& recordProperties = RecordPropertyCache::get(rt);
    const RecordAccessPlan& plan = recordProperties.planFor(rt, recordType.schemaId);
    const RecordSchema& schema = *plan.schema;
    const auto map = kolibri::UnownedRef<kolibri::JMap>(object);

    facebook::jsi::Object result(rt);
    for (size_t i = 0; i < schema.fields.size(); i++) {
      const RecordFieldSpec& field = schema.fields[i];
      kolibri::Ref<> value = map->get(env, field.name);
      result.setProperty(
        rt,
        plan.fieldNames[i],
        decodeFromJni(env, rt, value.get(), field.type)
      );
    }
    return result;
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::INT>() const {
    return facebook::jsi::Value(
      static_cast<double>(kolibri::UnownedRef<kolibri::JInteger>(object)->intValue(env))
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::BOX_INT>() const {
    return facebook::jsi::Value(
      static_cast<double>(kolibri::UnownedRef<kolibri::JInteger>(object)->intValue(env))
    );
  }

  // Note: int64 values above 2^53 lose precision; revisit with BigInt support.
  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::LONG>() const {
    return facebook::jsi::Value(
      static_cast<double>(kolibri::UnownedRef<kolibri::JLong>(object)->longValue(env))
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::BOX_LONG>() const {
    return facebook::jsi::Value(
      static_cast<double>(kolibri::UnownedRef<kolibri::JLong>(object)->longValue(env))
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::FLOAT>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JFloat>(object)->floatValue(env)
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::BOX_FLOAT>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JFloat>(object)->floatValue(env)
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::DOUBLE>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JDouble>(object)->doubleValue(env)
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::BOX_DOUBLE>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JDouble>(object)->doubleValue(env)
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::BOOLEAN>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JBoolean>(object)->booleanValue(env) != 0
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::BOX_BOOLEAN>() const {
    return facebook::jsi::Value(
      kolibri::UnownedRef<kolibri::JBoolean>(object)->booleanValue(env) != 0
    );
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::BOOLEAN_ARRAY>() const {
    const std::vector<jboolean> values =
      kolibri::UnownedRef<kolibri::JArray<jboolean>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(values[i] != 0);
    });
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::INT_ARRAY>() const {
    const std::vector<jint> values =
      kolibri::UnownedRef<kolibri::JArray<jint>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(static_cast<double>(values[i]));
    });
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::LONG_ARRAY>() const {
    const std::vector<jlong> values =
      kolibri::UnownedRef<kolibri::JArray<jlong>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(static_cast<double>(values[i]));
    });
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::FLOAT_ARRAY>() const {
    const std::vector<jfloat> values =
      kolibri::UnownedRef<kolibri::JArray<jfloat>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(values[i]);
    });
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::DOUBLE_ARRAY>() const {
    const std::vector<jdouble> values =
      kolibri::UnownedRef<kolibri::JArray<jdouble>>(object)->toVector(env);
    return buildJsArray(rt, values.size(), [&](const size_t i) {
      return facebook::jsi::Value(values[i]);
    });
  }

  /** A byte array lands in an ArrayBuffer rather than a JS array. */
  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::BYTE_ARRAY>() const {
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
  facebook::jsi::Value JniDecoder::operator()<CppType::ANY>() const {
    return decodeFromJniDynamic(env, rt, object);
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::UNIT>() const {
    return facebook::jsi::Value::undefined();
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::STRING>() const {
    return jsiStringFromJString(rt, env, static_cast<jstring>(object));
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::JS_VALUE>() const {
    return unwrapJsiHandle<jsi::JavaScriptValue>(env, rt, object);
  }

  template<>
  facebook::jsi::Value JniDecoder::operator()<CppType::JS_OBJECT>() const {
    return unwrapJsiHandle<jsi::JavaScriptObject>(env, rt, object);
  }
} // namespace expo::modules::v2
