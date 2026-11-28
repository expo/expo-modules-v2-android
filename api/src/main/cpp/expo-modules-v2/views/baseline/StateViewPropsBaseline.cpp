#include <expo-modules-v2/views/baseline/StateViewPropsBaseline.h>

#include <exception>
#include <memory>
#include <utility>
#include <vector>

#include <kolibri/Ref.h>
#include <kolibri/native_method.h>
#include <kolibri/utils.h>

#include <expo-modules-v2/converter/encoders/JniEncode.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <expo-modules-v2/utils/Log.h>
#include <expo-modules-v2/views/ViewPropsCodec.h>

namespace expo::modules::v2::views::baseline {
  namespace {
    struct JBaseline : kolibri::JavaClass<JBaseline> {
      static constexpr std::string_view descriptor = "io/github/expo/modules/v2/views/baseline/StateViewPropsBaseline";

      // clang-format off
      static constexpr Method<
        "decode",
        kolibri::Ref<>(jobject, jintArray, jobjectArray)
      > decode{};
      // clang-format on
    };

    /** What the props and the state held: the decoded Kotlin props, shared. */
    using DecodedProps = std::shared_ptr<const kolibri::GlobalRef<>>;

    /** The first prototype's `ViewPropsDecoder` (C++), on the codec's prop spec. */
    struct Baseline {
      Baseline(JNIEnv* env, jobject baseline, jobject decoder)
        : codec(env, decoder),
          kotlin(kolibri::GlobalRef<>::make(env, baseline)),
          objectClass(kolibri::findGlobalClass(env, "java/lang/Object")) {}

      ViewPropsCodec codec;
      kolibri::GlobalRef<> kotlin;
      kolibri::GlobalRef<> objectClass;
    };

    /**
     * `cloneProps` of the first prototype: picks the view's props out of the props object, converts
     * each to a Java object, and hands them to Kotlin in one call.
     */
    jlong commit(JNIEnv* env, jlong pointer, jlong valuePointer, jlong previousOwner) {
      auto& baseline = *reinterpret_cast<Baseline*>(pointer);
      const auto* value = reinterpret_cast<const jsi::JavaScriptValue*>(valuePointer);
      const auto* previous = reinterpret_cast<const DecodedProps*>(previousOwner);
      facebook::jsi::Runtime& rt = value->runtime();
      const facebook::jsi::Object props = value->value().getObject(rt);

      const facebook::jsi::Array names = props.getPropertyNames(rt);
      const size_t count = names.size(rt);
      std::vector<std::pair<jint, jobject>> converted;
      for (size_t i = 0; i < count; i++) {
        const facebook::jsi::String name = names.getValueAtIndex(rt, i).getString(rt);
        const auto index = baseline.codec.indexOf(name.utf8(rt));
        if (!index) {
          continue;
        }
        const facebook::jsi::Value propValue = props.getProperty(rt, name);
        if (propValue.isNull() || propValue.isUndefined()) {
          converted.emplace_back(static_cast<jint>(*index), nullptr);
          continue;
        }
        try {
          converted.emplace_back(
            static_cast<jint>(*index),
            encodeToJni(env, rt, propValue, baseline.codec.typeAt(*index))
          );
        } catch (const std::exception& error) {
          if (env->ExceptionCheck()) {
            env->ExceptionClear();
          }
          logLine("View prop '" + baseline.codec.nameAt(*index) + "' was not applied: " + error.what());
        }
      }
      if (converted.empty()) {
        return 0;
      }

      const auto size = static_cast<jsize>(converted.size());
      const auto indices = kolibri::Ref<>::adopt(env, env->NewIntArray(size));
      const auto objects = kolibri::Ref<>::adopt(
        env,
        env->NewObjectArray(size, static_cast<jclass>(baseline.objectClass.get()), nullptr)
      );
      for (jsize i = 0; i < size; i++) {
        const auto& [index, object] = converted[static_cast<size_t>(i)];
        env->SetIntArrayRegion(static_cast<jintArray>(indices.get()), i, 1, &index);
        env->SetObjectArrayElement(static_cast<jobjectArray>(objects.get()), i, object);
        if (object != nullptr) {
          env->DeleteLocalRef(object);
        }
      }

      const kolibri::Ref<> decoded = JBaseline::decode(
        env,
        baseline.kotlin.get(),
        previous != nullptr ? (*previous)->get() : nullptr,
        static_cast<jintArray>(indices.get()),
        static_cast<jobjectArray>(objects.get())
      );
      return reinterpret_cast<jlong>(new DecodedProps(
        std::make_shared<const kolibri::GlobalRef<>>(kolibri::GlobalRef<>::make(env, decoded.get()))
      ));
    }
  } // namespace

  void StateViewPropsBaselineNatives::registerNatives(JNIEnv* env) {
    JavaClass::registerNatives(env)
      .method(
        "nativeCreate",
        [](JNIEnv* env, jobject baseline, jobject decoder) -> jlong {
          return reinterpret_cast<jlong>(new Baseline(env, baseline, decoder));
        }
      )
      .method(
        "nativeDestroy",
        [](jlong pointer) {
          delete reinterpret_cast<Baseline*>(pointer);
        }
      )
      .method(
        "nativeCommit",
        [](JNIEnv* env, jlong pointer, jlong valuePointer, jlong previousOwner) -> jlong {
          return commit(env, pointer, valuePointer, previousOwner);
        }
      )
      .method(
        "nativeReference",
        // `ExpoViewState::getJNIReference`: a new local ref to the object the state holds.
        [](JNIEnv* env, jlong owner) -> jobject {
          return env->NewLocalRef((*reinterpret_cast<const DecodedProps*>(owner))->get());
        }
      )
      .method(
        "nativeRelease",
        [](jlong owner) {
          delete reinterpret_cast<const DecodedProps*>(owner);
        }
      )
      .commit();
  }
} // namespace expo::modules::v2::views::baseline
