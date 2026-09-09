#include <expo-modules-v2/jni/JEventSupport.h>

#include <stdexcept>
#include <string>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/binary/BinaryReader.h>
#include <kolibri/native_method.h>
#include <kolibri/string_utils.h>

#include <expo-modules-v2/converter/decoders/BufferDecode.h>
#include <expo-modules-v2/converter/decoders/JniDecode.h>
#include <expo-modules-v2/descriptor/EventSpec.h>
#include <expo-modules-v2/jni/JTrampoline.h>
#include <expo-modules-v2/jsi/JavaScriptRuntime.h>
#include <expo-modules-v2/objects/ObjectNativeState.h>
#include <expo-modules-v2/objects/RuntimeObjects.h>

namespace expo::modules::v2 {
  namespace {
    /** Where an emit lands: the object's JavaScript side in one runtime, and the payload's type. */
    struct Target {
      facebook::jsi::Runtime& rt;
      objects::RuntimeObjects& table;
      facebook::jsi::Object object;
      const ExpectedType& payloadType;
    };

    /**
     * Resolves the target of an emit, or nothing when there is nobody to deliver to: the runtime
     * is gone, the object has no JavaScript side here (any more), it declares no such event, it was
     * released, or no listener is registered. Nothing is decoded in those cases.
     */
    std::optional<Target> targetOf(
      JNIEnv* env,
      const jlong runtimePointer,
      const jlong objectId,
      const jstring name,
      std::string& eventName
    ) {
      auto* runtime = reinterpret_cast<jsi::JavaScriptRuntime*>(runtimePointer);
      if (runtime == nullptr) {
        return std::nullopt;
      }
      objects::RuntimeObjects* table = runtime->objects();
      if (table == nullptr) {
        return std::nullopt;
      }
      facebook::jsi::Runtime& rt = runtime->runtime();
      const auto id = static_cast<objects::ObjectId::Value>(objectId);
      eventName = kolibri::toStdString(env, name);

      facebook::jsi::Value target = table->lookup(rt, id);
      if (target.isUndefined()) {
        // JavaScript dropped the object while it still had listeners: they can never be removed,
        // so they go now, and Kotlin hears that this runtime stopped observing.
        table->dropListeners(rt, id);
        return std::nullopt;
      }
      if (table->listeners().count(id, eventName) == 0) {
        return std::nullopt;
      }

      facebook::jsi::Object object = target.getObject(rt);
      const std::shared_ptr<objects::ObjectNativeState> node =
        objects::ObjectNativeState::of(rt, object);
      if (node == nullptr || node->instance() == nullptr) {
        return std::nullopt;
      }
      const descriptor::EventSpec* spec = node->eventSpec(eventName);
      if (spec == nullptr) {
        return std::nullopt;
      }

      return Target{
        .rt = rt,
        .table = *table,
        .object = std::move(object),
        .payloadType = spec->payloadType,
      };
    }

    void deliver(const Target& target, const jlong objectId, const std::string& eventName, facebook::jsi::Value payload) {
      target.table.listeners().call(
        target.rt,
        static_cast<objects::ObjectId::Value>(objectId),
        eventName,
        target.object,
        &payload,
        1
      );
    }

    /** The payload arrived in a JNI slot, converted by Kotlin the way an export's result is. */
    void nativeEmit(
      JNIEnv* env,
      const jlong runtimePointer,
      const jlong objectId,
      const jstring name,
      const jobject value
    ) {
      std::string eventName;
      const std::optional<Target> target = targetOf(env, runtimePointer, objectId, name, eventName);
      if (!target.has_value()) {
        return;
      }

      deliver(*target, objectId, eventName, decodeFromJni(env, target->rt, value, target->payloadType));
    }

    /** The payload arrived on the shared binary buffer, [payloadLength] bytes of it. */
    void nativeEmitBuffered(
      JNIEnv* env,
      const jlong runtimePointer,
      const jlong objectId,
      const jstring name,
      const jint payloadLength
    ) {
      std::string eventName;
      const std::optional<Target> target = targetOf(env, runtimePointer, objectId, name, eventName);
      if (!target.has_value()) {
        return;
      }

      // Decoded in its own scope so the buffer claim ends before any listener runs: a listener may
      // call straight back into an export, which claims the buffer for its own arguments.
      facebook::jsi::Value payload = [&] {
        if (payloadLength == JTrampoline::kOverflowArgumentsSentinel) [[unlikely]] {
          const kolibri::Ref<> overflowed = JTrampoline::takeOverflowResult(env);
          return decodeFromJni(env, target->rt, overflowed.get(), target->payloadType);
        }

        const kolibri::binary::BinaryBuffer::Claim claim;
        if (!claim) {
          throw std::runtime_error(
            "The binary bridge buffer is already in use while emitting '" + eventName + "'"
          );
        }
        kolibri::binary::Reader reader{
          .position = claim.buffer().data(),
          .end = claim.buffer().data() + static_cast<size_t>(payloadLength)
        };
        return decodeFromBuffer(target->rt, reader, target->payloadType);
      }();

      deliver(*target, objectId, eventName, std::move(payload));
    }
  } // namespace

  void JEventNatives::registerNatives(JNIEnv* env) {
    kolibri::registerNative<JEventNatives>(env)
      .method<&nativeEmit>("nativeEmit", "(JJLjava/lang/String;Ljava/lang/Object;)V")
      .method<&nativeEmitBuffered>("nativeEmitBuffered", "(JJLjava/lang/String;I)V")
      .commit();
  }

  void JEventSupport::observe(
    JNIEnv* env,
    const jobject instance,
    const std::string& name,
    const jobject context,
    const bool observing
  ) {
    observe_(env, instance, name, context, static_cast<jboolean>(observing));
  }
} // namespace expo::modules::v2
