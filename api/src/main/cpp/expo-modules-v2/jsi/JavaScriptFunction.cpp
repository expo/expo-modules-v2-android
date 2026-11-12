#include <expo-modules-v2/jsi/JavaScriptFunction.h>

#include <atomic>
#include <exception>
#include <string>
#include <utility>
#include <vector>

#include <kolibri/LocalFrame.h>
#include <kolibri/class.h>
#include <kolibri/env.h>
#include <kolibri/string_utils.h>

#include <expo-modules-v2/converter/decoders/JniDecode.h>
#include <expo-modules-v2/converter/encoders/JniEncode.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2::jsi {
  namespace {
    using facebook::jsi::Function;
    using facebook::jsi::JSError;
    using facebook::jsi::Object;
    using facebook::jsi::PropNameID;
    using facebook::jsi::Runtime;
    using facebook::jsi::Value;

    /** What JavaScript threw or rejected with, as the three strings Kotlin's exception carries. */
    struct Failure {
      std::string code;
      std::string message;
      std::string stack;
    };

    std::string stringProperty(Runtime& rt, const Object& object, const char* name) {
      const Value property = object.getProperty(rt, name);
      return property.isString() ? property.getString(rt).utf8(rt) : std::string{};
    }

    Failure describe(Runtime& rt, const Value& reason) {
      if (reason.isObject()) {
        const Object object = reason.getObject(rt);
        Failure failure{
          .code = stringProperty(rt, object, "code"),
          .message = stringProperty(rt, object, "message"),
          .stack = stringProperty(rt, object, "stack"),
        };
        if (failure.message.empty()) {
          failure.message = "A JavaScript function threw a non-error object";
        }
        return failure;
      }

      if (reason.isString()) {
        return {.code = {}, .message = reason.getString(rt).utf8(rt), .stack = {}};
      }

      return {.code = {}, .message = "A JavaScript function threw a non-error value", .stack = {}};
    }

    Failure describe(Runtime& rt, const JSError& error) {
      Failure failure = describe(rt, error.value());
      // An engine-generated error carries its text in the JSError itself.
      if (failure.message.empty() || failure.message.starts_with("A JavaScript function threw")) {
        failure.message = error.getMessage();
      }
      if (failure.stack.empty()) {
        failure.stack = error.getStack();
      }
      return failure;
    }

    void reject(JNIEnv* env, jobject callback, const Failure& failure) {
      const kolibri::LocalFrame frame{env, 4};
      const kolibri::Ref<kolibri::JString> code =
        kolibri::Ref<kolibri::JString>::adopt(env, kolibri::toJString(env, failure.code));
      const kolibri::Ref<kolibri::JString> message =
        kolibri::Ref<kolibri::JString>::adopt(env, kolibri::toJString(env, failure.message));
      const kolibri::Ref<kolibri::JString> stack = failure.stack.empty()
        ? kolibri::Ref<kolibri::JString>{}
        : kolibri::Ref<kolibri::JString>::adopt(env, kolibri::toJString(env, failure.stack));

      kolibri::UnownedRef<JFunctionCallback>(callback)->reject(
        env,
        static_cast<jstring>(code.get()),
        static_cast<jstring>(message.get()),
        static_cast<jstring>(stack.get())
      );
    }

    /**
     * The function's result in the bridge form of the declared return type, or null for `Unit`,
     * which has no value to cross.
     */
    kolibri::Ref<> convertResult(
      JNIEnv* env,
      Runtime& rt,
      const FunctionSignature& signature,
      const Value& result
    ) {
      if (signature.returns.kind() == CppType::UNIT) {
        return {};
      }
      return kolibri::Ref<>::adopt(env, encodeToJni(env, rt, result, signature.returns));
    }

    /** Converts [result] and reports it, or reports why it could not be converted. */
    void settle(
      JNIEnv* env,
      Runtime& rt,
      const FunctionSignature& signature,
      jobject callback,
      const Value& result
    ) {
      kolibri::Ref<> converted;
      try {
        converted = convertResult(env, rt, signature, result);
      } catch (const JSError& error) {
        reject(env, callback, describe(rt, error));
        return;
      } catch (const std::exception& error) {
        reject(env, callback, {.code = {}, .message = error.what(), .stack = {}});
        return;
      }

      kolibri::UnownedRef<JFunctionCallback>(callback)->resolve(env, converted.get());
    }

    /**
     * What a promise's reactions need once it settles. It is shared by both of them because only
     * one ever runs, and by the host functions because those have to be copyable.
     */
    struct PendingCall {
      kolibri::GlobalRef<JFunctionCallback> callback;
      std::shared_ptr<const FunctionSignature> signature;
      std::atomic<bool> settled{false};

      PendingCall(
        kolibri::GlobalRef<JFunctionCallback> callback,
        std::shared_ptr<const FunctionSignature> signature
      ) : callback(std::move(callback)), signature(std::move(signature)) {
      }

      bool claim() { return !settled.exchange(true); }
    };

    bool thenOf(Runtime& rt, const Value& value, std::optional<Function>& then) {
      if (!value.isObject()) {
        return false;
      }
      const Value property = value.getObject(rt).getProperty(rt, "then");
      if (!property.isObject() || !property.getObject(rt).isFunction(rt)) {
        return false;
      }
      then.emplace(property.getObject(rt).getFunction(rt));
      return true;
    }

    void awaitPromise(
      JNIEnv* env,
      Runtime& rt,
      const Value& promise,
      const Function& then,
      jobject callback,
      const std::shared_ptr<const FunctionSignature>& signature
    ) {
      auto pending = std::make_shared<PendingCall>(
        kolibri::GlobalRef<JFunctionCallback>::make(env, callback),
        signature
      );

      Function onFulfilled = Function::createFromHostFunction(
        rt,
        PropNameID::forAscii(rt, "onFulfilled"),
        1,
        [pending](Runtime& rt, const Value&, const Value* args, size_t count) -> Value {
          if (pending->claim()) {
            JNIEnv* env = kolibri::getEnv();
            const kolibri::LocalFrame frame{env, 8};
            settle(
              env,
              rt,
              *pending->signature,
              pending->callback.get(),
              count > 0 ? Value(rt, args[0]) : Value::undefined()
            );
          }
          return Value::undefined();
        }
      );

      Function onRejected = Function::createFromHostFunction(
        rt,
        PropNameID::forAscii(rt, "onRejected"),
        1,
        [pending](Runtime& rt, const Value&, const Value* args, size_t count) -> Value {
          if (pending->claim()) {
            JNIEnv* env = kolibri::getEnv();
            reject(
              env,
              pending->callback.get(),
              describe(rt, count > 0 ? Value(rt, args[0]) : Value::undefined())
            );
          }
          return Value::undefined();
        }
      );

      then.callWithThis(rt, promise.getObject(rt), onFulfilled, onRejected);
    }
  } // namespace

  jobject JavaScriptFunction::create(
    JNIEnv* env,
    Runtime* runtime,
    Function function,
    std::shared_ptr<const FunctionSignature> signature
  ) {
    auto* self = new JavaScriptFunction(runtime, std::move(function), std::move(signature));
    return constructor.createRaw(env, reinterpret_cast<jlong>(self));
  }

  JavaScriptFunction::JavaScriptFunction(
    Runtime* runtime,
    Function function,
    std::shared_ptr<const FunctionSignature> signature
  ) : runtime_(runtime),
      function_(std::move(function)),
      signature_(std::move(signature)) {
    bindToScope(runtime_);
  }

  JavaScriptFunction::~JavaScriptFunction() {
    releaseScopedResources();
  }

  void JavaScriptFunction::call(
    JNIEnv* env,
    jobjectArray args,
    jobject callback,
    const jboolean awaitPromiseFlag
  ) const {
    Runtime& rt = *runtime_;
    const FunctionSignature& signature = *signature_;
    const size_t count = signature.params.size();
    const kolibri::LocalFrame frame{env, count + 8};

    // Everything up to the call can fail on a conversion, and so can the call itself; all of it
    // is reported through the callback, so Kotlin sees one outcome either way.
    Value result;
    try {
      if (!function_.has_value()) {
        throwRuntimeError({"The JavaScript runtime this function belongs to is gone"});
      }

      std::vector<Value> values;
      values.reserve(count);
      for (size_t i = 0; i < count; i++) {
        const kolibri::Ref<> element = kolibri::Ref<>::adopt(
          env,
          env->GetObjectArrayElement(args, static_cast<jsize>(i))
        );
        values.push_back(decodeFromJni(env, rt, element.get(), signature.params[i]));
      }

      result = function_->call(rt, static_cast<const Value*>(values.data()), count);

      std::optional<Function> then;
      if (awaitPromiseFlag != JNI_FALSE && thenOf(rt, result, then)) {
        awaitPromise(env, rt, result, *then, callback, signature_);
        return;
      }
    } catch (const JSError& error) {
      reject(env, callback, describe(rt, error));
      return;
    } catch (const std::exception& error) {
      reject(env, callback, {.code = {}, .message = error.what(), .stack = {}});
      return;
    }

    settle(env, rt, signature, callback, result);
  }

  void JavaScriptFunction::registerNatives(JNIEnv* env) {
    kolibri::registerNative<JavaScriptFunction>(env)
      .method<&JavaScriptFunction::call>(
        "call",
        "(J[Ljava/lang/Object;Lio/github/expo/modules/v2/jsi/JavaScriptFunctionCallback;Z)V"
      )
      .commit();
  }
} // namespace expo::modules::v2::jsi
