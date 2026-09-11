#include <expo-modules-v2/jni/JEventSupport.h>

#include <optional>
#include <stdexcept>
#include <string>
#include <utility>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/binary/BinaryReader.h>
#include <kolibri/native_method.h>

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
      /** Borrowed from the runtime's table; valid until a listener runs, which is why decoding comes first. */
      const std::vector<facebook::jsi::Value>& listeners;
      facebook::jsi::Object object;
      const descriptor::EventSpec& spec;
    };

    std::optional<Target> targetOf(
      const jlong runtimePointer,
      const jlong objectId,
      const jint eventIndex
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

      facebook::jsi::Value target = table->lookup(rt, id);
      if (target.isUndefined()) {
        // JavaScript dropped the object while it still had listeners: they can never be removed,
        // so they go now, and Kotlin hears that this runtime stopped observing.
        table->dropListeners(rt, id);
        return std::nullopt;
      }

      facebook::jsi::Object object = target.getObject(rt);
      const objects::ObjectNativeState* node = objects::ObjectNativeState::borrow(rt, object);
      if (node == nullptr || node->instance() == nullptr) {
        return std::nullopt;
      }
      const descriptor::EventSpec* spec = node->eventAt(eventIndex);
      if (spec == nullptr) {
        return std::nullopt;
      }

      // One lookup answers both "is anybody listening" and "who": the vector is borrowed from the
      // table and stays valid until a listener runs, and decoding happens before that.
      const std::vector<facebook::jsi::Value>* listeners = table->listeners().find(id, eventIndex);
      if (listeners == nullptr) {
        return std::nullopt;
      }

      return Target{
        .rt = rt,
        .listeners = *listeners,
        .object = std::move(object),
        .spec = *spec,
      };
    }

    void deliver(const Target& target, facebook::jsi::Value payload) {
      events::ListenerTable::call(target.rt, target.listeners, target.object, &payload, 1);
    }

    /** The payload arrived in a JNI slot, converted by Kotlin the way an export's result is. */
    void nativeEmit(
      JNIEnv* env,
      const jlong runtimePointer,
      const jlong objectId,
      const jint eventIndex,
      const jobject value
    ) {
      const std::optional<Target> target = targetOf(runtimePointer, objectId, eventIndex);
      if (!target.has_value()) {
        return;
      }

      deliver(*target, decodeFromJni(env, target->rt, value, target->spec.payloadType));
    }

    /** The payload arrived on the shared binary buffer, [payloadLength] bytes of it. */
    void nativeEmitBuffered(
      JNIEnv* env,
      const jlong runtimePointer,
      const jlong objectId,
      const jint eventIndex,
      const jint payloadLength
    ) {
      const std::optional<Target> target = targetOf(runtimePointer, objectId, eventIndex);
      if (!target.has_value()) {
        return;
      }

      // Decoded in its own scope so the buffer claim ends before any listener runs: a listener may
      // call straight back into an export, which claims the buffer for its own arguments.
      facebook::jsi::Value payload = [&] {
        if (payloadLength == JTrampoline::kOverflowArgumentsSentinel) [[unlikely]] {
          const kolibri::Ref<> overflowed = JTrampoline::takeOverflowResult(env);
          return decodeFromJni(env, target->rt, overflowed.get(), target->spec.payloadType);
        }

        const kolibri::binary::BinaryBuffer::Claim claim;
        if (!claim) {
          throw std::runtime_error(
            "The binary bridge buffer is already in use while emitting '" + target->spec.name + "'"
          );
        }
        kolibri::binary::Reader reader{
          .position = claim.buffer().data(),
          .end = claim.buffer().data() + static_cast<size_t>(payloadLength)
        };
        return decodeFromBuffer(target->rt, reader, target->spec.payloadType);
      }();

      deliver(*target, std::move(payload));
    }
  } // namespace

  void JEventNatives::registerNatives(JNIEnv* env) {
    kolibri::registerNative<JEventNatives>(env)
      .method<&nativeEmit>("nativeEmit", "(JJILjava/lang/Object;)V")
      .method<&nativeEmitBuffered>("nativeEmitBuffered", "(JJII)V")
      .commit();
  }

  void JEventSupport::observe(
    JNIEnv* env,
    const jobject instance,
    const int eventIndex,
    const jobject context,
    const bool observing
  ) {
    observe_(env, instance, static_cast<jint>(eventIndex), context, static_cast<jboolean>(observing));
  }
} // namespace expo::modules::v2
