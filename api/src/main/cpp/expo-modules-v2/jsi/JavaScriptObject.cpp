#include <expo-modules-v2/jsi/JavaScriptObject.h>

#include <expo-modules-v2/Config.h>
#include <expo-modules-v2/converter/JsiStringCodec.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <expo-modules-v2/objects/ObjectId.h>
#include <expo-modules-v2/objects/ObjectNativeState.h>
#include <kolibri/class.h>
#include <kolibri/string_utils.h>
#include <kolibri/array.h>

#include <array>
#include <string_view>

namespace expo::modules::v2::jsi {
  namespace {
    /**
     * The `Symbol.toStringTag` of each kind of array buffer view, in the order of the Kotlin
     * `ArrayBufferViewKind` entries: a kind crosses as its index here.
     */
    constexpr std::array<std::string_view, 12> kArrayBufferViewTags = {
      "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array",
      "Uint32Array", "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array", "DataView",
    };

    bool isArrayBufferView(facebook::jsi::Runtime& rt, const facebook::jsi::Object& object) {
      return rt.global()
        .getPropertyAsObject(rt, "ArrayBuffer")
        .getPropertyAsFunction(rt, "isView")
        .call(rt, facebook::jsi::Value(rt, object))
        .getBool();
    }

    /**
     * [name] as a property name of [rt]. A short ASCII name - nearly every one - is copied out with
     * one `GetStringRegion` and comes from the runtime's key cache, instead of turning into a heap
     * `std::string` and then a new JS string the runtime looks up in its identifier table on every
     * access. A copy of the cached name, because a getter or setter that the access runs can evict
     * it. Any other name crosses as UTF-16, so it keeps every character.
     */
    facebook::jsi::PropNameID propertyName(JNIEnv* env, facebook::jsi::Runtime& rt, jstring name) {
      const jsize length = env->GetStringLength(name);
      if (static_cast<size_t>(length) <= config::kMaxCachedPropertyNameLength) [[likely]] {
        jchar units[config::kMaxCachedPropertyNameLength];
        env->GetStringRegion(name, 0, length, units);
        char ascii[config::kMaxCachedPropertyNameLength];
        jchar accumulator = 0;
        for (jsize i = 0; i < length; ++i) {
          accumulator |= units[i];
          ascii[i] = static_cast<char>(units[i]);
        }

        if (accumulator < 0x80) [[likely]] {
          const std::string_view key{ascii, static_cast<size_t>(length)};
          if (const facebook::jsi::PropNameID* cached = RecordPropertyCache::get(rt).asciiKey(rt, key)) {
            return facebook::jsi::PropNameID(rt, *cached);
          }
        }
      }
      return facebook::jsi::PropNameID::forString(rt, jsiStringFromJString(rt, env, name));
    }
  } // namespace

  jobject JavaScriptObject::create(
    JNIEnv* env,
    facebook::jsi::Runtime* runtime,
    facebook::jsi::Object object
  ) {
    auto* self = new JavaScriptObject(runtime, std::move(object));
    return constructor.createRaw(env, reinterpret_cast<jlong>(self));
  }

  const facebook::jsi::Object& JavaScriptObject::object() const {
    return *object_;
  }

  facebook::jsi::Runtime& JavaScriptObject::runtime() const {
    return *runtime_;
  }

  jboolean JavaScriptObject::isArray() const noexcept {
    return object().isArray(runtime());
  }

  jboolean JavaScriptObject::isArrayBuffer() const noexcept {
    return object().isArrayBuffer(runtime());
  }

  jboolean JavaScriptObject::hasProperty(JNIEnv* env, jstring name) const {
    facebook::jsi::Runtime& rt = runtime();
    return object().hasProperty(rt, propertyName(env, rt, name));
  }

  jobject JavaScriptObject::getProperty(JNIEnv* env, jstring name) const {
    facebook::jsi::Runtime& rt = runtime();
    facebook::jsi::Value value = object().getProperty(rt, propertyName(env, rt, name));
    return JavaScriptValue::create(env, &rt, std::move(value));
  }

  jobjectArray JavaScriptObject::getPropertyNames(JNIEnv* env) const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Array names = object().getPropertyNames(rt);
    const size_t size = names.size(rt);
    const jobjectArray result = kolibri::JArray<kolibri::JString>::createRaw(env, size);

    if (size < kolibri::kMaxLocalFrameSize) [[likely]] {
      env->PushLocalFrame(size);
      for (size_t i = 0; i < size; i++) {
        std::string name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        jstring element = kolibri::toJString(env, name);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
      }
      env->PopLocalFrame(nullptr);
    } else {
      for (size_t i = 0; i < size; i++) {
        std::string name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        kolibri::Ref<> element = kolibri::Ref<>::adopt(env, kolibri::toJString(env, name));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
      }
    }
    return result;
  }

  jobjectArray JavaScriptObject::getArray(JNIEnv* env) const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Array array = object().getArray(rt);
    const size_t size = array.size(rt);

    const jobjectArray result = kolibri::JArray<JavaScriptValue>::createRaw(env, size);
    if (size < kolibri::kMaxLocalFrameSize) [[likely]] {
      env->PushLocalFrame(size);
      for (size_t i = 0; i < size; i++) {
        const jobject element = JavaScriptValue::create(env, &rt, array.getValueAtIndex(rt, i));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
      }
      env->PopLocalFrame(nullptr);
    } else {
      for (size_t i = 0; i < size; i++) {
        kolibri::Ref<JavaScriptValue> element = kolibri::Ref<JavaScriptValue>::adopt(
          env,
          JavaScriptValue::create(env, &rt, array.getValueAtIndex(rt, i))
        );
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element.get());
      }
    }
    return result;
  }

  void JavaScriptObject::setBoolProperty(JNIEnv* env, jstring name, jboolean value) const {
    facebook::jsi::Runtime& rt = runtime();
    object().setProperty(rt, propertyName(env, rt, name), facebook::jsi::Value(static_cast<bool>(value)));
  }

  void JavaScriptObject::setDoubleProperty(JNIEnv* env, jstring name, jdouble value) const {
    facebook::jsi::Runtime& rt = runtime();
    object().setProperty(rt, propertyName(env, rt, name), facebook::jsi::Value(value));
  }

  void JavaScriptObject::setStringProperty(JNIEnv* env, jstring name, jstring value) const {
    facebook::jsi::Runtime& rt = runtime();
    facebook::jsi::Value jsValue =
      value
        ? facebook::jsi::Value(jsiStringFromJString(rt, env, value))
        : facebook::jsi::Value::null();
    object().setProperty(rt, propertyName(env, rt, name), std::move(jsValue));
  }

  void JavaScriptObject::setJSValueProperty(
    JNIEnv* env,
    jstring name,
    JavaScriptValue* value
  ) const {
    facebook::jsi::Runtime& rt = runtime();
    facebook::jsi::Value jsValue =
      value
        ? facebook::jsi::Value(rt, value->value())
        : facebook::jsi::Value::null();
    object().setProperty(rt, propertyName(env, rt, name), jsValue);
  }

  void JavaScriptObject::setObjectProperty(
    JNIEnv* env,
    jstring name,
    JavaScriptObject* value
  ) const {
    facebook::jsi::Runtime& rt = runtime();
    facebook::jsi::Value jsValue =
      value
        ? facebook::jsi::Value(rt, value->object())
        : facebook::jsi::Value::null();
    object().setProperty(rt, propertyName(env, rt, name), std::move(jsValue));
  }

  void JavaScriptObject::unsetProperty(JNIEnv* env, jstring name) const {
    facebook::jsi::Runtime& rt = runtime();
    object().setProperty(rt, propertyName(env, rt, name), facebook::jsi::Value::undefined());
  }

  JavaScriptObject::JavaScriptObject(
    facebook::jsi::Runtime* runtime,
    facebook::jsi::Object object
  ) : runtime_(runtime),
      object_(std::move(object)) {
    bindToScope(runtime_);
  }

  JavaScriptObject::~JavaScriptObject() {
    releaseScopedResources();
  }

  jobject JavaScriptObject::getArrayBufferViewBytes(JNIEnv* env) const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Object& view = object();

    if (!isArrayBufferView(rt, view)) {
      return nullptr;
    }

    const facebook::jsi::ArrayBuffer buffer = view.getPropertyAsObject(rt, "buffer").getArrayBuffer(rt);
    const size_t offset = static_cast<size_t>(view.getProperty(rt, "byteOffset").getNumber());
    const size_t length = static_cast<jlong>(view.getProperty(rt, "byteLength").getNumber());

    static uint8_t empty = 0;
    uint8_t* data = length == 0 ? &empty : buffer.data(rt) + offset;
    return env->NewDirectByteBuffer(data, length);
  }

  jint JavaScriptObject::getArrayBufferViewKind() const {
    facebook::jsi::Runtime& rt = runtime();
    const facebook::jsi::Object& view = object();

    if (!isArrayBufferView(rt, view)) {
      return -1;
    }

    // `Object.prototype.toString` reads the view's `Symbol.toStringTag`, which for a typed array is
    // a getter that returns the name of its kind - even for a subclass, unlike `constructor.name`.
    const std::string description = rt.global()
      .getPropertyAsObject(rt, "Object")
      .getPropertyAsObject(rt, "prototype")
      .getPropertyAsFunction(rt, "toString")
      .callWithThis(rt, view)
      .getString(rt)
      .utf8(rt);

    // "[object Float32Array]"
    constexpr std::string_view prefix = "[object ";
    std::string_view tag = description;
    if (!tag.starts_with(prefix) || !tag.ends_with(']')) {
      return -1;
    }
    tag = tag.substr(prefix.size(), tag.size() - prefix.size() - 1);

    for (size_t i = 0; i < kArrayBufferViewTags.size(); ++i) {
      if (kArrayBufferViewTags[i] == tag) {
        return static_cast<jint>(i);
      }
    }
    return -1;
  }

  jobject JavaScriptObject::nativeInstance(JNIEnv* env) const {
    const auto node = objects::ObjectNativeState::of(runtime(), object());
    return node == nullptr ? nullptr : node->state()->instanceRef().newLocal(env);
  }

  void JavaScriptObject::registerNatives(JNIEnv* env) {
    kolibri::registerNative<JavaScriptObject>(env)
      .method<&JavaScriptObject::isArray>("isArray")
      .method<&JavaScriptObject::isArrayBuffer>("isArrayBuffer")
      .method<
        &JavaScriptObject::getArrayBufferViewBytes,
        kolibri::Ref<kolibri::JByteBuffer>(kolibri::NativePointer)
      >("getArrayBufferViewBytes")
      .method<&JavaScriptObject::getArrayBufferViewKind>("getArrayBufferViewKind")
      .method<&JavaScriptObject::hasProperty>("hasProperty")
      .method<
        &JavaScriptObject::getProperty,
        kolibri::Ref<JavaScriptValue>(kolibri::NativePointer, jstring)
      >("getProperty")
      .method<
        &JavaScriptObject::getPropertyNames,
        kolibri::Ref<kolibri::JArray<jstring>>(kolibri::NativePointer)
      >("getPropertyNames")
      .method<
        &JavaScriptObject::getArray,
        kolibri::Ref<kolibri::JArray<JavaScriptValue>>(kolibri::NativePointer)
      >("getArray")
      .method<&JavaScriptObject::setBoolProperty>("setBoolProperty")
      .method<&JavaScriptObject::setDoubleProperty>("setDoubleProperty")
      .method<&JavaScriptObject::setStringProperty>("setStringProperty")
      .method<&JavaScriptObject::setJSValueProperty>("setJSValueProperty")
      .method<&JavaScriptObject::setObjectProperty>("setObjectProperty")
      .method<&JavaScriptObject::unsetProperty>("unsetProperty")
      .method<
        &JavaScriptObject::nativeInstance,
        kolibri::Ref<objects::JExpoObject>(kolibri::NativePointer)
      >("nativeInstance")
      .commit();
  }
} // namespace expo::modules::v2::jsi
