#include <expo-modules-v2/JniMethodInvoker.h>

#include <expo-modules-v2/async/AsyncRuntimeState.h>
#include <expo-modules-v2/jni/JTrampoline.h>
#include <expo-modules-v2/converter/decoders/BufferDecode.h>
#include <expo-modules-v2/converter/decoders/JniDecode.h>
#include <expo-modules-v2/converter/encoders/BufferEncode.h>
#include <expo-modules-v2/converter/encoders/JniEncode.h>

#include <array>
#include <string>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/LocalFrame.h>
#include <kolibri/exception.h>
#include <kolibri/Ref.h>


namespace expo::modules::v2 {
  using descriptor::FunctionSpec;

  namespace {
    [[noreturn]] void throwArgumentCountMismatch(
      facebook::jsi::Runtime& rt,
      const FunctionSpec& spec,
      const size_t count
    ) {
      throw facebook::jsi::JSError(
        rt,
        spec.name + " expects " + std::to_string(spec.argTypes.size()) +
        " argument(s), but received " + std::to_string(count)
      );
    }

    facebook::jsi::Value callBoolean(const JniMethodCall& call) {
      const jboolean result = call.env->CallNonvirtualBooleanMethodA(
        call.receiver,
        call.declaringClass,
        call.method,
        call.args
      );
      kolibri::checkAndThrowPending(call.env);
      return facebook::jsi::Value(result != 0);
    }

    facebook::jsi::Value callInt(const JniMethodCall& call) {
      const jint result = call.env->CallNonvirtualIntMethodA(
        call.receiver,
        call.declaringClass,
        call.method,
        call.args
      );
      kolibri::checkAndThrowPending(call.env);
      return facebook::jsi::Value(static_cast<double>(result));
    }

    facebook::jsi::Value callLong(const JniMethodCall& call) {
      const jlong result = call.env->CallNonvirtualLongMethodA(
        call.receiver,
        call.declaringClass,
        call.method,
        call.args
      );
      kolibri::checkAndThrowPending(call.env);
      return facebook::jsi::Value(static_cast<double>(result));
    }

    facebook::jsi::Value callFloat(const JniMethodCall& call) {
      const jfloat result = call.env->CallNonvirtualFloatMethodA(
        call.receiver,
        call.declaringClass,
        call.method,
        call.args
      );
      kolibri::checkAndThrowPending(call.env);
      return facebook::jsi::Value(result);
    }

    facebook::jsi::Value callDouble(const JniMethodCall& call) {
      const jdouble result = call.env->CallNonvirtualDoubleMethodA(
        call.receiver,
        call.declaringClass,
        call.method,
        call.args
      );
      kolibri::checkAndThrowPending(call.env);
      return facebook::jsi::Value(result);
    }

    facebook::jsi::Value callVoid(const JniMethodCall& call) {
      call.env->CallNonvirtualVoidMethodA(call.receiver, call.declaringClass, call.method, call.args);
      kolibri::checkAndThrowPending(call.env);
      return facebook::jsi::Value::undefined();
    }

    facebook::jsi::Value callObject(const JniMethodCall& call) {
      const kolibri::Ref<> result = kolibri::Ref<>::adopt(
        call.env,
        call.env->CallNonvirtualObjectMethodA(call.receiver, call.declaringClass, call.method, call.args)
      );
      kolibri::checkAndThrowPending(call.env);
      return decodeFromJni(
        call.env,
        call.rt,
        result.get(),
        call.returnType
      );
    }

    facebook::jsi::Value callStaticObject(const JniMethodCall& call) {
      const kolibri::Ref<> result = kolibri::Ref<>::adopt(
        call.env,
        call.env->CallStaticObjectMethodA(call.declaringClass, call.method, call.args)
      );
      kolibri::checkAndThrowPending(call.env);
      return decodeFromJni(
        call.env,
        call.rt,
        result.get(),
        call.returnType
      );
    }

    facebook::jsi::Value callBufferedPayload(const JniMethodCall& call) {
      const jint written = call.env->CallNonvirtualIntMethodA(
        call.receiver,
        call.declaringClass,
        call.method,
        call.args
      );
      kolibri::checkAndThrowPending(call.env);

      if (written == JTrampoline::kOverflowArgumentsSentinel) [[unlikely]] {
        const kolibri::Ref<> result = JTrampoline::takeOverflowResult(call.env);
        return decodeFromJni(
          call.env,
          call.rt,
          result.get(),
          call.returnType
        );
      }

      const kolibri::binary::BinaryBuffer::Claim claim;
      kolibri::binary::Reader reader{
        .position = claim.buffer().data(),
        .end = claim.buffer().data() + static_cast<size_t>(written)
      };
      return decodeFromBuffer(call.rt, reader, call.returnType);
    }

    /**
     * Packs the arguments of a call whose payload did not fit the shared buffer, and returns how
     * many `jvalue` slots that used - which is where the next parameter, if any, goes.
     */
    size_t encodeOverflowArgs(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      const facebook::jsi::Value* args,
      jvalue* values
    ) {
      const auto overflowSlots = JTrampoline::prepareOverflowArguments(env);

      // Payload values land in the overflow slots CONSECUTIVELY, in the order they would have been
      // written to the buffer — not at their declared argument index. That is what lets the Kotlin
      // cursor read them incrementally, so a trampoline body reads its payload the same way whether
      // it arrived in the buffer or in these slots.
      size_t slot = 0;
      size_t payloadSlot = 0;
      for (size_t i = 0; i < spec.argTypes.size(); i++) {
        const ExpectedType& type = spec.argTypes[i];
        if (type.usesBuffer()) {
          overflowSlots->setElement(
            env,
            static_cast<jsize>(payloadSlot++),
            encodeToJni(env, rt, args[i], type)
          );
        } else {
          values[slot++] = encodeToJniValue(env, rt, args[i], type);
        }
      }
      values[slot].i = JTrampoline::kOverflowArgumentsSentinel;
      return slot + 1;
    }

    template<JniMethodInvoker Method>
    facebook::jsi::Value invokeWithOverflowArgs(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      jobject receiver,
      const facebook::jsi::Value* args
    ) {
      std::array<jvalue, FunctionSpec::kMaxArgs> values{};
      const kolibri::LocalFrame frame{env, FunctionSpec::kMaxArgs};

      encodeOverflowArgs(rt, env, spec, args, values.data());

      return Method({
        .rt = rt,
        .env = env,
        .receiver = receiver,
        .declaringClass = spec.declaringClass,
        .method = spec.method,
        .returnType = spec.returnType,
        .args = values.data()
      });
    }

    /** Every argument in its own JNI slot. Returns how many slots that used. */
    size_t encodeDirectArgs(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      const facebook::jsi::Value* args,
      jvalue* values
    ) {
      for (size_t i = 0; i < spec.argTypes.size(); i++) {
        values[i] = encodeToJniValue(
          env,
          rt,
          args[i],
          spec.argTypes[i]
        );
      }
      return spec.argTypes.size();
    }

    /**
     * Buffered arguments into the shared buffer, the rest into slots, then a trailing slot with the
     * payload length. Returns how many slots that used, or throws `BufferOverflow`.
     */
    size_t encodeBufferedArgs(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      const facebook::jsi::Value* args,
      jvalue* values
    ) {
      // The claim ends with this function, not with the call: the payload stays in the buffer for
      // the callee to read, but nothing else may write it until the callee has done so.
      const kolibri::binary::BinaryBuffer::Claim bufferClaim;
      auto& out = bufferClaim.buffer();
      out.clear();

      size_t slot = 0;
      for (size_t i = 0; i < spec.argTypes.size(); i++) {
        const ExpectedType& type = spec.argTypes[i];

        if (type.usesBuffer()) {
          encodeToBuffer(rt, args[i], type, out);
        } else {
          values[slot] = encodeToJniValue(env, rt, args[i], type);
          slot++;
        }
      }

      values[slot].i = static_cast<jint>(out.size());
      return slot + 1;
    }

    template<JniMethodInvoker Method>
    facebook::jsi::Value invokeWithDirectArgsBody(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      jobject receiver,
      const facebook::jsi::Value* args
    ) {
      std::array<jvalue, FunctionSpec::kMaxArgs> values{};

      encodeDirectArgs(rt, env, spec, args, values.data());

      return Method({
        .rt = rt,
        .env = env,
        .receiver = receiver,
        .declaringClass = spec.declaringClass,
        .method = spec.method,
        .returnType = spec.returnType,
        .args = values.data()
      });
    }

    template<JniMethodInvoker Method>
    facebook::jsi::Value invokeWithBufferedArgsBody(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      jobject receiver,
      const facebook::jsi::Value* args
    ) {
      // We need to encode size, so +1
      std::array<jvalue, FunctionSpec::kMaxArgs + 1> values{};

      try {
        encodeBufferedArgs(rt, env, spec, args, values.data());
      } catch (const kolibri::binary::BufferOverflow&) {
        return invokeWithOverflowArgs<Method>(
          rt,
          env,
          spec,
          receiver,
          args
        );
      }

      return Method({
        .rt = rt,
        .env = env,
        .receiver = receiver,
        .declaringClass = spec.declaringClass,
        .method = spec.method,
        .returnType = spec.returnType,
        .args = values.data()
      });
    }

    template<bool NeedsLocalFrame, JniMethodInvoker Method>
    facebook::jsi::Value invokeWithDirectArgs(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      jobject receiver,
      const facebook::jsi::Value* args
    ) {
      if constexpr (NeedsLocalFrame) {
        // The frame owns every local created by direct object conversions until the Java call
        // completes. Primitive-only signatures instantiate the other branch and pay no JNI frame
        // cost or runtime condition.
        const kolibri::LocalFrame frame{env, FunctionSpec::kMaxArgs};
        return invokeWithDirectArgsBody<Method>(
          rt,
          env,
          spec,
          receiver,
          args
        );
      } else {
        return invokeWithDirectArgsBody<Method>(
          rt,
          env,
          spec,
          receiver,
          args
        );
      }
    }

    template<bool NeedsLocalFrame, JniMethodInvoker Method>
    facebook::jsi::Value invokeWithBufferedArgs(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      jobject receiver,
      const facebook::jsi::Value* args
    ) {
      if constexpr (NeedsLocalFrame) {
        const kolibri::LocalFrame frame{env, FunctionSpec::kMaxArgs};
        return invokeWithBufferedArgsBody<Method>(
          rt,
          env,
          spec,
          receiver,
          args
        );
      } else {
        return invokeWithBufferedArgsBody<Method>(
          rt,
          env,
          spec,
          receiver,
          args
        );
      }
    }

    template<bool HasBufferedArgs, bool NeedsLocalFrame, JniMethodInvoker Method>
    facebook::jsi::Value invokeFunction(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      jobject receiver,
      const facebook::jsi::Value* args,
      size_t count
    ) {
      if (count != spec.argTypes.size()) [[unlikely]] {
        throwArgumentCountMismatch(rt, spec, count);
      }

      if constexpr (HasBufferedArgs) {
        return invokeWithBufferedArgs<NeedsLocalFrame, Method>(
          rt,
          env,
          spec,
          receiver,
          args
        );
      } else {
        return invokeWithDirectArgs<NeedsLocalFrame, Method>(
          rt,
          env,
          spec,
          receiver,
          args
        );
      }
    }

    /**
     * A `suspend` export.
     *
     * The promise is created and returned before Kotlin has produced anything: the trampoline gets
     * the handle, starts the coroutine, and returns void immediately. Settling happens later, from
     * a job on the JS thread, through `AsyncRuntimeState`.
     *
     * The return-kind axis the synchronous invokers switch on collapses here - every async
     * trampoline returns void - so only the buffered-arguments axis remains.
     */
    template<bool HasBufferedArgs>
    facebook::jsi::Value invokeAsyncFunction(
      facebook::jsi::Runtime& rt,
      JNIEnv* env,
      const FunctionSpec& spec,
      jobject receiver,
      const facebook::jsi::Value* args,
      size_t count
    ) {
      if (count != spec.argTypes.size()) [[unlikely]] {
        throwArgumentCountMismatch(rt, spec, count);
      }

      async::AsyncRuntimeState* state = async::AsyncRuntimeState::find(rt);
      if (state == nullptr) [[unlikely]] {
        throw facebook::jsi::JSError(
          rt,
          spec.name + " is a suspend export, but this runtime has no async context"
        );
      }

      // TODO(@lukmccall): can we pass reference instead of cloning
      const uint64_t id = state->beginCall(spec.returnType.clone());

      // +1 over the argument slots for the trailing Promise.
      std::array<jvalue, FunctionSpec::kMaxArgs + 1> values{};
      const kolibri::LocalFrame frame{env, FunctionSpec::kMaxArgs + 1};

      size_t slot;
      try {
        if constexpr (HasBufferedArgs) {
          try {
            slot = encodeBufferedArgs(rt, env, spec, args, values.data());
          } catch (const kolibri::binary::BufferOverflow&) {
            slot = encodeOverflowArgs(rt, env, spec, args, values.data());
          }
        } else {
          slot = encodeDirectArgs(rt, env, spec, args, values.data());
        }
      } catch (...) {
        // Nothing has seen the promise yet, so drop it rather than leaving it pending forever.
        state->discard(id);
        throw;
      }

      const kolibri::Ref<JPromise> kotlinPromise = state->newKotlinPromise(env, id);
      values[slot].l = kotlinPromise.get();

      try {
        env->CallNonvirtualVoidMethodA(receiver, spec.declaringClass, spec.method, values.data());
        kolibri::checkAndThrowPending(env);
      } catch (const std::exception& e) {
        state->reject(rt, id, "ERR_TRAMPOLINE", e.what(), "");
      }

      state->drainInlineSettles(env);

      return state->finishCall(rt, id);
    }

    template<bool HasBufferedArgs, bool NeedsLocalFrame>
    FunctionInvoker invokerForReturnKind(const CppType kind) {
      switch (kind) {
        case CppType::BOOLEAN:
          return &invokeFunction<HasBufferedArgs, NeedsLocalFrame, &callBoolean>;
        case CppType::INT:
          return &invokeFunction<HasBufferedArgs, NeedsLocalFrame, &callInt>;
        case CppType::LONG:
          return &invokeFunction<HasBufferedArgs, NeedsLocalFrame, &callLong>;
        case CppType::FLOAT:
          return &invokeFunction<HasBufferedArgs, NeedsLocalFrame, &callFloat>;
        case CppType::DOUBLE:
          return &invokeFunction<HasBufferedArgs, NeedsLocalFrame, &callDouble>;
        case CppType::UNIT:
          return &invokeFunction<HasBufferedArgs, NeedsLocalFrame, &callVoid>;
        default:
          return &invokeFunction<HasBufferedArgs, NeedsLocalFrame, &callObject>;
      }
    }

    template<bool HasBufferedArgs, bool NeedsLocalFrame>
    FunctionInvoker invokerForReturnType(const ExpectedType& returnType) {
      if (returnType.usesBuffer()) {
        return &invokeFunction<HasBufferedArgs, NeedsLocalFrame, &callBufferedPayload>;
      }
      return invokerForReturnKind<HasBufferedArgs, NeedsLocalFrame>(returnType.kind());
    }
  } // namespace

  FunctionInvoker selectStaticObjectInvoker(
    const bool hasBufferedArgs,
    const bool needsLocalFrame
  ) {
    if (hasBufferedArgs) {
      return needsLocalFrame
               ? &invokeFunction<true, true, &callStaticObject>
               : &invokeFunction<true, false, &callStaticObject>;
    }
    return needsLocalFrame
             ? &invokeFunction<false, true, &callStaticObject>
             : &invokeFunction<false, false, &callStaticObject>;
  }

  FunctionInvoker selectFunctionInvoker(
    const ExpectedType& returnType,
    const bool hasBufferedArgs,
    const bool needsLocalFrame,
    const bool async
  ) {
    if (async) {
      return hasBufferedArgs
               ? &invokeAsyncFunction<true>
               : &invokeAsyncFunction<false>;
    }

    if (hasBufferedArgs) {
      return needsLocalFrame
               ? invokerForReturnType<true, true>(returnType)
               : invokerForReturnType<true, false>(returnType);
    }
    return needsLocalFrame
             ? invokerForReturnType<false, true>(returnType)
             : invokerForReturnType<false, false>(returnType);
  }
} // namespace expo::modules::v2
