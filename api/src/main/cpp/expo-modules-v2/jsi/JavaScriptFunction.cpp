#include <expo-modules-v2/jsi/JavaScriptFunction.h>

#include <atomic>
#include <exception>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include <kolibri/LocalFrame.h>
#include <kolibri/array.h>
#include <kolibri/class.h>
#include <kolibri/env.h>
#include <kolibri/native_method.h>
#include <kolibri/string_utils.h>

#include <expo-modules-v2/async/AsyncRuntimeState.h>
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

    /**
     * The property as a string, or empty. A getter can throw, and this runs while a failure is
     * being reported, so it never does: a second failure would lose the first.
     */
    std::string stringProperty(Runtime& rt, const Object& object, const char* name) noexcept {
      try {
        const Value property = object.getProperty(rt, name);
        return property.isString() ? property.getString(rt).utf8(rt) : std::string{};
      } catch (...) {
        return {};
      }
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
        return {
          .code = {},
          .message = reason.getString(rt).utf8(rt),
          .stack = {}
        };
      }

      return {
        .code = {},
        .message = "A JavaScript function threw a non-error value",
        .stack = {}
      };
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

    void reject(
      JNIEnv* env,
      const jobject callback,
      const Failure& failure
    ) {
      const kolibri::Ref<kolibri::JString> code =
        kolibri::Ref<kolibri::JString>::adopt(env, kolibri::toJString(env, failure.code));
      const kolibri::Ref<kolibri::JString> message =
        kolibri::Ref<kolibri::JString>::adopt(env, kolibri::toJString(env, failure.message));
      const kolibri::Ref<kolibri::JString> stack = failure.stack.empty()
                                                     ? kolibri::Ref<kolibri::JString>{}
                                                     : kolibri::Ref<kolibri::JString>::adopt(env,
                                                       kolibri::toJString(env, failure.stack));

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
        reject(
          env,
          callback,
          {
            .code = {},
            .message = error.what(),
            .stack = {}
          }
        );
        return;
      }

      kolibri::UnownedRef<JFunctionCallback>(callback)->resolve(env, converted.get());
    }

    /**
     * What a promise's reactions need once it settles. It is shared by both of them because only
     * one ever runs, and by the host functions because those have to be copyable.
     */
    struct PendingCall {
      // TODO: use similar optimalization that was used in promises to reduce the number of global refs
      kolibri::GlobalRef<JFunctionCallback> callback;
      std::shared_ptr<const FunctionSignature> signature;
      std::atomic<bool> settled{false};

      PendingCall(
        kolibri::GlobalRef<JFunctionCallback> callback,
        std::shared_ptr<const FunctionSignature> signature
      ) : callback(std::move(callback)),
          signature(std::move(signature)) {
      }

      bool claim() { return !settled.exchange(true); }
    };

    /** The value's `then` method when it is a thenable, the way `await` decides to wait for it. */
    std::optional<Function> thenOf(Runtime& rt, const Value& value) {
      if (!value.isObject()) {
        return std::nullopt;
      }
      const Value property = value.getObject(rt).getProperty(rt, "then");
      if (!property.isObject() || !property.getObject(rt).isFunction(rt)) {
        return std::nullopt;
      }
      return property.getObject(rt).getFunction(rt);
    }

    /**
     * The locals [settle] holds at once: the three strings of a rejection. A frame's size is the
     * capacity it reserves, not a limit.
     */
    constexpr size_t kSettleLocals = 3;

    Function onFulfilled(Runtime& rt, const std::shared_ptr<PendingCall>& pending) {
      return Function::createFromHostFunction(
        rt,
        PropNameID::forAscii(rt, "onFulfilled"),
        1,
        [pending](
          Runtime& rt,
          const Value&,
          const Value* args,
          size_t count
        ) -> Value {
          if (pending->claim()) {
            JNIEnv* env = kolibri::getEnv();
            // A reaction runs inside a microtask drain, so its locals live until the JNI call
            // around that drain returns. A conversion that throws partway leaves the raw locals
            // it made, and the frame frees them when this reaction ends instead.
            const kolibri::LocalFrame frame{env, kSettleLocals};
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
    }

    Function onRejected(Runtime& rt, const std::shared_ptr<PendingCall>& pending) {
      return Function::createFromHostFunction(
        rt,
        PropNameID::forAscii(rt, "onRejected"),
        1,
        [pending](
          Runtime& rt,
          const Value&,
          const Value* args,
          size_t count
        ) -> Value {
          if (pending->claim()) {
            reject(
              kolibri::getEnv(),
              pending->callback.get(),
              describe(rt, count > 0 ? Value(rt, args[0]) : Value::undefined())
            );
          }
          return Value::undefined();
        }
      );
    }

    /**
     * Runs the microtask queue so the promise behind [pending] can settle before the call returns.
     * A promise that waits on anything else - a timer, native work - cannot settle while the JS
     * thread is held here, so the call fails instead of waiting forever.
     */
    void settleBeforeReturning(JNIEnv* env, Runtime& rt, PendingCall& pending) {
      try {
        rt.drainMicrotasks();
      } catch (const JSError& error) {
        // A microtask threw. It may not belong to this call, but when this call is still pending
        // it will not settle in this drain either.
        if (pending.claim()) {
          reject(env, pending.callback.get(), describe(rt, error));
        }
        return;
      } catch (const std::exception& error) {
        if (pending.claim()) {
          reject(env, pending.callback.get(), {.code = {}, .message = error.what(), .stack = {}});
        }
        return;
      }

      if (pending.claim()) {
        reject(
          env,
          pending.callback.get(),
          {
            .code = {},
            .message = "The JavaScript function returned a promise that its microtasks did not "
            "settle, and a plain lambda on the JavaScript thread cannot wait for more. "
            "Declare the parameter `suspend`, or make the lambda return `Promise<T>`.",
            .stack = {},
          }
        );
      }
    }

    /**
     * Reports what [promise] settles with. With [settleNow], the call settles before it returns:
     * see [settleBeforeReturning].
     */
    void settlePromise(
      JNIEnv* env,
      Runtime& rt,
      const Value& promise,
      const Function& then,
      const jobject callback,
      const std::shared_ptr<const FunctionSignature>& signature,
      const bool settleNow
    ) {
      const auto pending = std::make_shared<PendingCall>(
        kolibri::GlobalRef<JFunctionCallback>::make(env, callback),
        signature
      );

      // A thenable's `then` can throw before or after it calls either reaction. The call settles
      // once: a throw rejects it only when no reaction got to it first.
      try {
        then.callWithThis(
          rt,
          promise.getObject(rt),
          onFulfilled(rt, pending),
          onRejected(rt, pending)
        );
      } catch (const JSError& error) {
        if (pending->claim()) {
          reject(env, callback, describe(rt, error));
        }
      } catch (const std::exception& error) {
        if (pending->claim()) {
          reject(
            env,
            callback,
            {
              .code = {},
              .message = error.what(),
              .stack = {}
            }
          );
        }
      }

      if (settleNow) {
        settleBeforeReturning(env, rt, *pending);
      }
    }
  } // namespace

  jobject JavaScriptFunction::create(
    JNIEnv* env,
    Runtime* runtime,
    Function function,
    std::shared_ptr<const FunctionSignature> signature
  ) {
    // The runtime's own context, not whichever runtime the calling thread made last: that is the
    // scheduler a call has to reach, and the one whose close has to fail it.
    const async::AsyncRuntimeState* state = async::AsyncRuntimeState::find(*runtime);
    const jobject asyncContext = state != nullptr ? state->context() : nullptr;

    auto* self = new JavaScriptFunction(runtime, std::move(function), std::move(signature));
    return constructor.createRaw(env, reinterpret_cast<jlong>(self), asyncContext);
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
    const kolibri::UnownedRef<kolibri::JObjectArray> args,
    const jobject callback,
    const jboolean settleNow
  ) const {
    Runtime& rt = *runtime_;
    const FunctionSignature& signature = *signature_;

    Value result;
    std::optional<Function> then;
    try {
      if (!function_.has_value()) {
        throwRuntimeError({"The JavaScript runtime this function belongs to is gone"});
      }

      const size_t count = signature.params.size();
      std::vector<Value> values;
      values.reserve(count);
      for (size_t i = 0; i < count; i++) {
        const kolibri::Ref<kolibri::JObject> element = args->getElement(env, static_cast<jsize>(i));
        values.push_back(decodeFromJni(env, rt, element.get(), signature.params[i]));
      }

      result = function_->call(rt, static_cast<const Value*>(values.data()), count);
      // A promise is transparent: the call reports what it settles with, as `await` would.
      then = thenOf(rt, result);
    } catch (const JSError& error) {
      reject(env, callback, describe(rt, error));
      return;
    } catch (const std::exception& error) {
      reject(
        env,
        callback,
        {
          .code = {},
          .message = error.what(),
          .stack = {}
        }
      );
      return;
    }

    if (then.has_value()) {
      settlePromise(env, rt, result, *then, callback, signature_, settleNow != JNI_FALSE);
    } else {
      settle(env, rt, signature, callback, result);
    }
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
