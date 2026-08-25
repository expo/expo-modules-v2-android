#include "FollyBenchmark.h"

#ifdef EXPO_FOLLY_BENCHMARK

#include <chrono>
#include <string>

#include <folly/json.h>
#include <jsi/JSIDynamic.h>

#include "BinaryTestPipeline.h"
#include <expo-modules-v2/converter/decoders/JniDecode.h>
#include <expo-modules-v2/converter/encoders/JniEncode.h>
#include <kolibri/Ref.h>
#include <kolibri/box.h>
#include <kolibri/class.h>
#include <kolibri/env.h>
#include <kolibri/string_utils.h>

namespace expo::modules::v2 {

  namespace {
    // Manually maps a folly::dynamic to the Java object graph a Kotlin module receives in React
    // Native — the equivalent of ReadableNativeMap.toHashMap() / ReadableNativeArray.toArrayList():
    // OBJECT -> java.util.HashMap, ARRAY -> java.util.ArrayList, every number -> boxed
    // java.lang.Double (RN's number semantic), BOOL -> Boolean, STRING -> String, NULLT -> null.
    // Returns a fresh local reference (children are released as soon as the container holds them).
    jobject follyToJObject(JNIEnv* env, const folly::dynamic& value) {
      switch (value.type()) {
        case folly::dynamic::NULLT:
          return nullptr;
        case folly::dynamic::BOOL:
          return kolibri::JBoolean::valueOf(env, static_cast<jboolean>(value.getBool())).release();
        case folly::dynamic::INT64:
          return kolibri::JDouble::valueOf(env, static_cast<jdouble>(value.getInt())).release();
        case folly::dynamic::DOUBLE:
          return kolibri::JDouble::valueOf(env, static_cast<jdouble>(value.getDouble())).release();
        case folly::dynamic::STRING:
          return kolibri::toJString(env, value.getString());
        case folly::dynamic::ARRAY: {
          auto list = kolibri::JArrayList::constructor(env, static_cast<jint>(value.size()));
          for (const folly::dynamic& element: value) {
            kolibri::Ref<> jElement = kolibri::Ref<>::adopt(env, follyToJObject(env, element));
            list->add(env, jElement);
          }
          return list.release();
        }
        case folly::dynamic::OBJECT: {
          auto map = kolibri::JHashMap::constructor(env);
          for (const auto& [key, item]: value.items()) {
            kolibri::Ref<> jItem = kolibri::Ref<>::adopt(env, follyToJObject(env, item));
            map->put(env, key.getString(), jItem);
          }
          return map.release();
        }
      }
      return nullptr;
    }
  } // namespace

  void installFollyBench(facebook::jsi::Runtime& runtime, facebook::jsi::Object& core) {
    auto follyBench = facebook::jsi::Function::createFromHostFunction(
      runtime,
      facebook::jsi::PropNameID::forAscii(runtime, "__follyBench"),
      2,
      [](facebook::jsi::Runtime& rt, const facebook::jsi::Value&, const facebook::jsi::Value* args, size_t count) -> facebook::jsi::Value {
        if (count < 2 || !args[1].isNumber()) {
          throw facebook::jsi::JSError(rt, "__follyBench(payload, iters) expects a value and a count");
        }
        const int iters = static_cast<int>(args[1].getNumber());
        const facebook::jsi::Value& payload = args[0];

        double sink = 0;
        const auto timeNs = [&](auto&& op) {
          // Warmup.
          for (int i = 0; i < iters / 10 + 1; i++) {
            sink += op();
          }
          const auto start = std::chrono::steady_clock::now();
          for (int i = 0; i < iters; i++) {
            sink += op();
          }
          const auto elapsed = std::chrono::steady_clock::now() - start;
          return static_cast<double>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count()
          ) / iters;
        };

        // React Native's representation: facebook::jsi::Value -> folly::dynamic -> facebook::jsi::Value (what
        // TurboModules do around a dynamic argument/result).
        const double follyNs = timeNs([&] {
          folly::dynamic d = facebook::jsi::dynamicFromValue(rt, payload);
          facebook::jsi::Value back = facebook::jsi::valueFromDynamic(rt, d);
          return back.isObject() ? 1.0 : 0.0;
        });

        // The classic-bridge flavor: the folly::dynamic additionally crosses as a JSON string.
        const double follyJsonNs = timeNs([&] {
          folly::dynamic d = facebook::jsi::dynamicFromValue(rt, payload);
          const std::string json = folly::toJson(d);
          folly::dynamic parsed = folly::parseJson(json);
          facebook::jsi::Value back = facebook::jsi::valueFromDynamic(rt, parsed);
          return back.isObject() ? 1.0 : 0.0;
        });

        // Our codec: facebook::jsi::Value -> binary buffer -> facebook::jsi::Value (dynamic flavor — the same
        // "type unknown until runtime" contract folly::dynamic serves). Production-faithful on
        // oversized payloads: the failed encode attempt is paid, then the value crosses as Java
        // objects instead (the overflow fallback), so this lane never throws.
        JNIEnv* env = ::expo::kolibri::getEnv();
        const double binaryNs = timeNs([&] {
          auto& out = ::expo::kolibri::binary::BinaryBuffer::shared();
          if (encodeJSIValue(rt, payload, out)) {
            facebook::jsi::Value back = decodeJSIValue(rt, out.data(), out.size());
            return back.isObject() ? 1.0 : 0.0;
          }
          jobject o = encodeToJniDynamic(env, rt, payload);
          facebook::jsi::Value back = decodeFromJniDynamic(env, rt, o);
          env->DeleteLocalRef(o);
          return back.isObject() ? 1.0 : 0.0;
        });

        // === The full app-behavior lanes: the value ends as Kotlin-consumable Java objects. ===

        // RN's delivery: facebook::jsi::Value -> folly::dynamic, then the manual per-field JNI mapping into
        // HashMap/ArrayList/boxed values (ReadableNativeMap.toHashMap()-equivalent).
        const double follyKtNs = timeNs([&] {
          folly::dynamic d = facebook::jsi::dynamicFromValue(rt, payload);
          jobject o = follyToJObject(env, d);
          const double ok = o != nullptr ? 1.0 : 0.0;
          env->DeleteLocalRef(o);
          return ok;
        });

        // The object-slot delivery: Java objects built per-field in C++ straight from JSI, no
        // intermediate representation — the path dynamic (`Any`) values and buffer overflows
        // take (encodeToJniDynamic).
        const double elementKtNs = timeNs([&] {
          jobject o = encodeToJniDynamic(env, rt, payload);
          const double ok = o != nullptr ? 1.0 : 0.0;
          env->DeleteLocalRef(o);
          return ok;
        });

        return facebook::jsi::String::createFromUtf8(
          rt,
          std::to_string(follyNs) + ":" + std::to_string(follyJsonNs) + ":" +
          std::to_string(binaryNs) + ":" + std::to_string(follyKtNs) + ":" +
          std::to_string(elementKtNs) + ":" + std::to_string(sink)
        );
      }
    );
    core.setProperty(runtime, "__follyBench", std::move(follyBench));
  }
} // namespace expo::modules::v2

#else

namespace expo::modules::v2 {
  void installFollyBench(facebook::jsi::Runtime&, facebook::jsi::Object&) {
    // Compiled without EXPO_FOLLY_BENCHMARK: the host function is deliberately absent, and the
    // Kotlin runner reports how to enable it.
  }
} // namespace expo::modules::v2

#endif
