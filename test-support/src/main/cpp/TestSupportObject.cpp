#include "TestSupportObject.h"

#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include <expo-jsi/ChainedNativeState.h>
#include <jsi/instrumentation.h>

#include <kolibri/class.h>
#include <kolibri/env.h>

#include "BinaryTestPipeline.h"
#include "FollyBenchmark.h"
#include "JSIConverters.h"
#include "KolibriArrayCheck.h"
#include <expo-modules-v2/converter/decoders/JniDecode.h>
#include <expo-modules-v2/converter/encoders/JniEncode.h>
#include <expo-modules-v2/modules/ModuleNativeState.h>

namespace expo::modules::v2 {

  using expo::jsi::ChainedNativeState;
  using expo::jsi::ChainedNativeStateOf;

  namespace {
    void expectSmoke(bool condition, const char* message) {
      if (!condition) {
        throw std::runtime_error(message);
      }
    }

    // Two independent state types for the native-state chain smoke test.
    struct SmokeCountState : ChainedNativeStateOf<SmokeCountState> {
      explicit SmokeCountState(int value) : value(value) {}
      int value;
    };

    struct SmokeLabelState : ChainedNativeStateOf<SmokeLabelState> {
      explicit SmokeLabelState(std::string value) : value(std::move(value)) {}
      std::string value;
    };

    // Exercises ChainedNativeState end to end on a fresh object: typed lookup on an empty
    // object, two coexisting states, and same-type shadowing. If `moduleObject` is given (a
    // materialized `expo.modules.<name>` object), also proves its ModuleNativeState is a
    // findable chain node.
    std::string runNativeStateChainSmoke(facebook::jsi::Runtime& rt, const facebook::jsi::Object* moduleObject) {
      facebook::jsi::Object object(rt);
      expectSmoke(
        ChainedNativeState::find<SmokeCountState>(rt, object) == nullptr,
        "an empty object should carry no chained state"
      );

      ChainedNativeState::attach(rt, object, std::make_shared<SmokeCountState>(42));
      ChainedNativeState::attach(rt, object, std::make_shared<SmokeLabelState>("beta"));

      const auto count = ChainedNativeState::find<SmokeCountState>(rt, object);
      expectSmoke(count != nullptr && count->value == 42, "count state lost after chaining");
      const auto label = ChainedNativeState::find<SmokeLabelState>(rt, object);
      expectSmoke(label != nullptr && label->value == "beta", "label state not found");

      // A second state of the same type shadows the earlier node without dropping the others.
      ChainedNativeState::attach(rt, object, std::make_shared<SmokeCountState>(7));
      expectSmoke(
        ChainedNativeState::find<SmokeCountState>(rt, object)->value == 7,
        "the newest same-type state should shadow the older one"
      );
      expectSmoke(
        ChainedNativeState::find<SmokeLabelState>(rt, object)->value == "beta",
        "label state lost after shadowing"
      );

      if (moduleObject != nullptr) {
        const auto state = ChainedNativeState::find<ModuleNativeState>(rt, *moduleObject);
        expectSmoke(
          state != nullptr && state->instance() != nullptr,
          "expected a chained ModuleNativeState on the module object"
        );
        expectSmoke(
          state->functionBinders().size() == 1 && state->functionBinders()[0].name() == "add",
          "expected the module function binder in ModuleNativeState"
        );
        expectSmoke(
          state->propertyBinders().size() == 1 && state->propertyBinders()[0].name() == "answer",
          "expected the module property binder in ModuleNativeState"
        );
      }

      return "ok";
    }
  } // namespace

  void installTestSupport(facebook::jsi::Runtime& rt) {
    auto suite = facebook::jsi::Object(rt);

    // Round-trips a value through both converters: JSI -> JNI (driven by the
    // expected-type encoding in arg 1) -> JSI (value-driven). See
    // `decodeExpectedType`.
    auto convertRoundTrip = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forAscii(rt, "__convertRoundTrip"),
      2,
      [](facebook::jsi::Runtime& rt, const facebook::jsi::Value&, const facebook::jsi::Value* args, size_t count) -> facebook::jsi::Value {
        if (count < 2 || !args[1].isObject() || !args[1].getObject(rt).isArray(rt)) {
          throw facebook::jsi::JSError(
            rt,
            "__convertRoundTrip(value, typeCodes) expects a value and an "
            "array of CppType codes"
          );
        }
        try {
          facebook::jsi::Array codeArray = args[1].getObject(rt).getArray(rt);
          std::vector<int> codes;
          codes.reserve(codeArray.size(rt));
          for (size_t i = 0; i < codeArray.size(rt); i++) {
            codes.push_back(static_cast<int>(codeArray.getValueAtIndex(rt, i).asNumber()));
          }
          ExpectedType expected = detail::decodeExpectedTypeCodes(codes);
          JNIEnv* env = kolibri::getEnv();
          kolibri::Ref<> asJava = kolibri::Ref<>::adopt(
            env,
            encodeToJni(env, rt, args[0], expected)
          ); // JSI -> JNI
          return decodeFromJniDynamic(env, rt, asJava.get()); // JNI -> JSI
        } catch (const facebook::jsi::JSError&) {
          throw;
        } catch (const std::exception& e) {
          throw facebook::jsi::JSError(rt, std::string("convertRoundTrip failed: ") + e.what());
        }
      }
    );
    suite.setProperty(rt, "__convertRoundTrip", std::move(convertRoundTrip));

    // Exercises the C++-native binary converters (BinaryCodec.h): for each `kind` it
    // encodes arg 0 with the TYPED JSI encoder (the decoders trust the buffer, so the payload
    // must match T exactly), decodes it into the C++ type via fromBinary<T>, re-encodes it via
    // toBinary, and decodes the bytes back into a JS value. Each leg is a single pass.
    auto binaryNativeRoundTrip = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forAscii(rt, "__binaryNativeRoundTrip"),
      2,
      [](facebook::jsi::Runtime& rt, const facebook::jsi::Value&, const facebook::jsi::Value* args, size_t count) -> facebook::jsi::Value {
        if (count < 2 || !args[1].isNumber()) {
          throw facebook::jsi::JSError(
            rt,
            "__binaryNativeRoundTrip(value, kind) expects a value and a numeric kind"
          );
        }
        // The buffer is schema-directed: encode with the TYPED encoder (JS-shape validation and
        // numeric conversion happen there), read/write raw via the C++ converters, and decode
        // the untagged bytes with the TYPED decoder.
        const auto through = [&]<typename T>(const ExpectedType& expected) -> facebook::jsi::Value {
          auto& out = kolibri::binary::BinaryBuffer::shared();
          if (!encodeJSIValue(rt, args[0], expected, out)) {
            throw facebook::jsi::JSError(rt, "__binaryNativeRoundTrip: payload did not fit");
          }
          T native = kolibri::binary::fromBinary<T>(out.data(), out.size());
          if (!kolibri::binary::toBinary(native, out)) {
            throw facebook::jsi::JSError(rt, "__binaryNativeRoundTrip: re-encoded payload did not fit");
          }
          return decodeJSIValue(rt, expected, out.data(), out.size());
        };
        const auto listOf = [](ExpectedType element) {
          return ExpectedType::list(std::move(element));
        };
        const auto mapOf = [](ExpectedType value) {
          return ExpectedType::map(std::move(value));
        };
        try {
          switch (static_cast<int>(args[1].getNumber())) {
            case 0:
              return through.operator()<bool>(ExpectedType(LeafType::BOOLEAN));
            case 1:
              return through.operator()<int32_t>(ExpectedType(LeafType::INT));
            case 2:
              return through.operator()<int64_t>(ExpectedType(LeafType::LONG));
            case 3:
              return through.operator()<float>(ExpectedType(LeafType::FLOAT));
            case 4:
              return through.operator()<double>(ExpectedType(LeafType::DOUBLE));
            case 5:
              return through.operator()<std::string>(ExpectedType(LeafType::STRING));
            case 6:
              return through.operator()<std::vector<int32_t> >(
                listOf(ExpectedType(LeafType::BOX_INT))
              );
            case 7:
              return through.operator()<std::vector<std::vector<int32_t> > >(
                listOf(listOf(ExpectedType(LeafType::BOX_INT)))
              );
            case 8:
              return through.operator()<std::unordered_map<std::string, double> >(
                mapOf(ExpectedType(LeafType::BOX_DOUBLE))
              );
            case 9:
              return through.operator()<std::vector<double> >(
                listOf(ExpectedType(LeafType::BOX_DOUBLE))
              );
            case 10: {
              ExpectedType nullableDouble(LeafType::BOX_DOUBLE, true);
              return through.operator()<std::optional<double> >(nullableDouble);
            }
            case 11:
              return through.operator()<std::vector<uint8_t> >(ExpectedType(LeafType::BYTE_ARRAY));
            default:
              throw facebook::jsi::JSError(rt, "__binaryNativeRoundTrip: unknown kind");
          }
        } catch (const facebook::jsi::JSError&) {
          throw;
        } catch (const std::exception& e) {
          throw facebook::jsi::JSError(rt, std::string("__binaryNativeRoundTrip failed: ") + e.what());
        }
      }
    );
    suite.setProperty(rt, "__binaryNativeRoundTrip", std::move(binaryNativeRoundTrip));

    // Exercises the compile-time-typed converters (JSIConverters.h): for each
    // `kind` it extracts arg 0 as a specific native C++ type via
    // fromJSIValue<T> and re-emits it via toJSIValue.
    auto nativeRoundTrip = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forAscii(rt, "__nativeRoundTrip"),
      2,
      [](facebook::jsi::Runtime& rt, const facebook::jsi::Value&, const facebook::jsi::Value* args, size_t count) -> facebook::jsi::Value {
        if (count < 2 || !args[1].isNumber()) {
          throw facebook::jsi::JSError(
            rt,
            "__nativeRoundTrip(value, kind) expects a value and a numeric "
            "kind"
          );
        }
        const facebook::jsi::Value& v = args[0];
        switch (static_cast<int>(args[1].getNumber())) {
          case 0:
            return toJSIValue(rt, fromJSIValue<bool>(rt, v));
          case 1:
            return toJSIValue(rt, fromJSIValue<int32_t>(rt, v));
          case 2:
            return toJSIValue(rt, fromJSIValue<int64_t>(rt, v));
          case 3:
            return toJSIValue(rt, fromJSIValue<float>(rt, v));
          case 4:
            return toJSIValue(rt, fromJSIValue<double>(rt, v));
          case 5:
            return toJSIValue(rt, fromJSIValue<std::string>(rt, v));
          case 6:
            return toJSIValue(rt, fromJSIValue<std::vector<int32_t>>(rt, v));
          case 7:
            return toJSIValue(rt, fromJSIValue<std::vector<std::vector<int32_t>>>(rt, v));
          case 8:
            return toJSIValue(rt, fromJSIValue<std::unordered_map<std::string, double>>(rt, v));
          default:
            throw facebook::jsi::JSError(rt, "__nativeRoundTrip: unknown kind");
        }
      }
    );
    suite.setProperty(rt, "__nativeRoundTrip", std::move(nativeRoundTrip));

    auto nativeStateChainSmoke = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forAscii(rt, "__nativeStateChainSmoke"),
      1,
      [](facebook::jsi::Runtime& rt, const facebook::jsi::Value&, const facebook::jsi::Value* args, size_t count) -> facebook::jsi::Value {
        try {
          if (count >= 1 && args[0].isObject()) {
            const facebook::jsi::Object module = args[0].getObject(rt);
            return facebook::jsi::String::createFromUtf8(rt, runNativeStateChainSmoke(rt, &module));
          }
          return facebook::jsi::String::createFromUtf8(rt, runNativeStateChainSmoke(rt, nullptr));
        } catch (const std::exception& e) {
          throw facebook::jsi::JSError(rt, std::string("__nativeStateChainSmoke failed: ") + e.what());
        }
      }
    );
    suite.setProperty(rt, "__nativeStateChainSmoke", std::move(nativeStateChainSmoke));

    auto collectGarbage = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forAscii(rt, "__collectGarbage"),
      0,
      [](facebook::jsi::Runtime& rt, const facebook::jsi::Value&, const facebook::jsi::Value*, size_t) -> facebook::jsi::Value {
        rt.instrumentation().collectGarbage("ExpoTestSupport.__collectGarbage");
        return facebook::jsi::Value::undefined();
      }
    );
    suite.setProperty(rt, "__collectGarbage", std::move(collectGarbage));

    // Exercises the kolibri JNI array API (kolibri/array.h) against the live JVM — creation,
    // regions, pins, object/nested arrays, array-typed method tokens. Returns "ok" or the first
    // failure (KolibriArrayCheck.cpp).
    auto kolibriArrayCheck = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forAscii(rt, "__kolibriArrayCheck"),
      0,
      [](facebook::jsi::Runtime& rt, const facebook::jsi::Value&, const facebook::jsi::Value*, size_t) -> facebook::jsi::Value {
        try {
          return facebook::jsi::String::createFromUtf8(rt, runKolibriArrayCheck());
        } catch (const std::exception& e) {
          throw facebook::jsi::JSError(rt, std::string("__kolibriArrayCheck failed: ") + e.what());
        }
      }
    );
    suite.setProperty(rt, "__kolibriArrayCheck", std::move(kolibriArrayCheck));

    // Registers array-typed native methods (signatures derived from the C++ parameter types) on
    // the Kotlin test fixture `expo.modules.v2.testapp.KolibriArrayFixture` — only meaningful on
    // the test-app test classpath.
    auto kolibriArrayBindFixture = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forAscii(rt, "__kolibriArrayBindFixture"),
      0,
      [](facebook::jsi::Runtime& rt, const facebook::jsi::Value&, const facebook::jsi::Value*, size_t) -> facebook::jsi::Value {
        try {
          bindKolibriArrayFixture();
          return facebook::jsi::Value::undefined();
        } catch (const std::exception& e) {
          throw facebook::jsi::JSError(rt, std::string("__kolibriArrayBindFixture failed: ") + e.what());
        }
      }
    );
    suite.setProperty(rt, "__kolibriArrayBindFixture", std::move(kolibriArrayBindFixture));

    installFollyBench(rt, suite); // no-op unless built with -DEXPO_FOLLY_BENCHMARK=ON

    rt.global().setProperty(rt, "ExpoTestSupport", std::move(suite));
  }
} // namespace expo::modules::v2
