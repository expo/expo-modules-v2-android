#include <expo-modules-v2/converter/encoders/JniEncode.h>

#include <kolibri/binary/BinaryBuffer.h>

#include <expo-modules-v2/converter/encoders/BufferEncode.h>
#include <expo-modules-v2/converter/encoders/EncodeCommon.h>
#include <expo-modules-v2/jni/JTrampoline.h>
#include <expo-modules-v2/converter/JsiStringCodec.h>
#include <expo-modules-v2/converter/encoders/JniEncoder.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <expo-modules-v2/utils/Throw.h>
#include <kolibri/array.h>
#include <kolibri/box.h>
#include <kolibri/class.h>
#include <kolibri/string_utils.h>

namespace expo::modules::v2 {
  namespace {
    using kolibri::binary::BinaryBuffer;
    using kolibri::binary::Tag;

    /**
     * Writes [value] for a slot of [type] - `Any`, or a List or Map of it - in the tagged dynamic
     * format, checking it as the walk in [JniEncoder] does. False when the value has a shape that
     * walk treats differently - a List that is not an array, a Map that is an array, a function or
     * an ArrayBuffer, a null the type does not take - so the walk sees it and answers as before.
     */
    bool writeSlotDynamic(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& value,
      const ExpectedType& type,
      BinaryBuffer& out
    ) {
      if (value.isUndefined() || value.isNull()) {
        if (!type.nullable()) {
          return false;
        }
        out.writeTag(Tag::NULLTAG);
        return true;
      }

      switch (type.kind()) {
        case CppType::LIST: {
          if (!value.isObject()) {
            return false;
          }
          const facebook::jsi::Object object = value.getObject(rt);
          if (!object.isArray(rt)) {
            return false;
          }
          const facebook::jsi::Array array = object.getArray(rt);
          const size_t size = array.size(rt);
          const ExpectedType& elementType = type.listElement();
          out.writeTag(Tag::LIST);
          out.write<>(static_cast<int32_t>(size));
          out.writeTag(Tag::TAGGED);
          for (size_t i = 0; i < size; i++) {
            if (!writeSlotDynamic(rt, array.getValueAtIndex(rt, i), elementType, out)) {
              return false;
            }
          }
          return true;
        }
        case CppType::MAP: {
          if (!value.isObject()) {
            return false;
          }
          const facebook::jsi::Object object = value.getObject(rt);
          if (object.isArray(rt) || object.isFunction(rt) || object.isArrayBuffer(rt)) {
            return false;
          }
          const facebook::jsi::Array names = object.getPropertyNames(rt);
          const size_t size = names.size(rt);
          const ExpectedType& valueType = type.mapValue();
          out.writeTag(Tag::MAP);
          out.write<>(static_cast<int32_t>(size));
          for (size_t i = 0; i < size; i++) {
            const facebook::jsi::String key = names.getValueAtIndex(rt, i).getString(rt);
            writeJsiString(rt, key, out);
            if (!writeSlotDynamic(rt, object.getProperty(rt, key), valueType, out)) {
              return false;
            }
          }
          return true;
        }
        default:
          encodeToBufferDynamic(rt, value, out);
          return true;
      }
    }

    /**
     * A dynamic value for a JNI slot, built in one pass: written onto the shared buffer here and
     * read back by Kotlin in one call, instead of with JNI calls per element. Null when the buffer
     * is in use, the value does not fit, or [writeSlotDynamic] leaves it to the walk.
     */
    jobject encodeSlotThroughBuffer(
      JNIEnv* env,
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& value,
      const ExpectedType& type
    ) {
      jint length = 0;
      {
        const BinaryBuffer::Claim claim;
        if (!claim) [[unlikely]] {
          return nullptr;
        }
        BinaryBuffer& out = claim.buffer();
        out.clear();
        try {
          if (!writeSlotDynamic(rt, value, type, out)) [[unlikely]] {
            return nullptr;
          }
        } catch (const kolibri::binary::BufferOverflow&) {
          return nullptr;
        }
        length = static_cast<jint>(out.size());
      }
      // The claim ends before Kotlin reads the payload, as it does for a call's arguments.
      return JTrampoline::readSlotDynamic(env, length).release();
    }

    /**
     * [encodeSlotThroughBuffer], or a walk of the value when it turns the value down. Out of line,
     * so a slot of any other type does not carry the claim and its exception handler: inlined, they
     * would cost every [encodeToJni] call more saved registers.
     */
    [[gnu::noinline]] jobject encodeDynamicSlot(
      JNIEnv* env,
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& value,
      const ExpectedType& type
    ) {
      if (const jobject built = encodeSlotThroughBuffer(env, rt, value, type)) {
        return built;
      }
      return encodeToJniSlot(env, rt, value, type);
    }
  } // namespace

  jvalue encodeToJniValue(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type
  ) {
    jvalue out{};

    switch (type.kind()) {
      case CppType::BOOLEAN:
        out.z = boolOf(rt, value) != 0;
        return out;
      case CppType::INT:
        out.i = static_cast<int32_t>(numberOf(rt, value));
        return out;
      case CppType::LONG:
        out.j = static_cast<int64_t>(numberOf(rt, value));
        return out;
      case CppType::FLOAT:
        out.f = static_cast<float>(numberOf(rt, value));
        return out;
      case CppType::DOUBLE:
        out.d = numberOf(rt, value);
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

    // A buffered type reaches a slot only as an overflow, which the buffer already turned down. A
    // scalar costs one JNI call at most, which is less than the trip through the buffer.
    if (type.plainDynamic() && !type.usesBuffer() && value.isObject()) {
      return encodeDynamicSlot(env, rt, value, type);
    }

    return type.visit<jobject>(
      JniEncoder{
        .env = env,
        .rt = rt,
        .value = value
      }
    );
  }

  jobject encodeToJniSlot(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& value,
    const ExpectedType& type
  ) {
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
        .value = value,
        .slotWalk = true
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
      return jstringFromJsiString(env, rt, value.getString(rt));
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
        throwJSError(rt, {"Cannot convert a JS function to a Java value"}); // TODO: callbacks
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
        const kolibri::Ref<> javaKey = kolibri::Ref<>::adopt(env, jstringFromJsiString(env, rt, key));
        kolibri::JHashMap::put(env, map, javaKey.get(), element);
        env->DeleteLocalRef(element);
      }
      return map;
    }

    throwJSError(rt, {"Cannot convert this JS value to a Java value"});
  }
} // namespace expo::modules::v2
