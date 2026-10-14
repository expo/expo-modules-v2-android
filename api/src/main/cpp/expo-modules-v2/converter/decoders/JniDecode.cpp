#include <expo-modules-v2/converter/decoders/JniDecode.h>

#include <optional>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/jni/JDynamicTypes.h>
#include <expo-modules-v2/jni/JTrampoline.h>
#include <expo-modules-v2/converter/decoders/BufferDecode.h>
#include <expo-modules-v2/converter/decoders/DynamicJniDecoder.h>
#include <expo-modules-v2/converter/decoders/JniDecoder.h>

namespace expo::modules::v2 {
  namespace {
    /**
     * A dynamic value in a JNI slot, read in one pass: Kotlin writes it onto the shared buffer with
     * the tagged dynamic codec, in one call, instead of answering a JNI call per element. Nothing
     * when the buffer is in use or Kotlin turns the value down.
     */
    std::optional<facebook::jsi::Value> decodeSlotFromBuffer(
      JNIEnv* env,
      facebook::jsi::Runtime& rt,
      jobject object
    ) {
      const kolibri::binary::BinaryBuffer::Claim claim;
      if (!claim) [[unlikely]] {
        return std::nullopt;
      }

      const jint written = JTrampoline::writeSlotDynamic(env, object);
      if (written == JTrampoline::kOverflowArgumentsSentinel) [[unlikely]] {
        return std::nullopt;
      }

      kolibri::binary::Reader reader{
        .position = claim.buffer().data(),
        .end = claim.buffer().data() + static_cast<size_t>(written)
      };
      return decodeFromBufferDynamic(rt, reader);
    }

    /**
     * [decodeSlotFromBuffer], or a walk of the slot when it turns the value down. Out of line, so a
     * slot of any other type does not carry the claim and the optional value: inlined, they would
     * cost every [decodeFromJni] call five more saved registers.
     */
    [[gnu::noinline]] facebook::jsi::Value decodeDynamicSlot(
      JNIEnv* env,
      facebook::jsi::Runtime& rt,
      jobject object,
      const ExpectedType& type
    ) {
      if (std::optional<facebook::jsi::Value> value = decodeSlotFromBuffer(env, rt, object)) {
        return std::move(*value);
      }
      return decodeFromJniSlot(env, rt, object, type);
    }
  } // namespace

  facebook::jsi::Value decodeFromJni(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object,
    const ExpectedType& type
  ) {
    if (object == nullptr) {
      return facebook::jsi::Value::null();
    }

    // A buffered type reaches a slot only as an overflow, which the buffer already turned down.
    if (type.plainDynamic() && !type.usesBuffer()) {
      return decodeDynamicSlot(env, rt, object, type);
    }

    return type.visit<facebook::jsi::Value>(
      JniDecoder{.env = env, .rt = rt, .object = object}
    );
  }

  facebook::jsi::Value decodeFromJniSlot(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object,
    const ExpectedType& type
  ) {
    if (object == nullptr) {
      return facebook::jsi::Value::null();
    }

    return type.visit<facebook::jsi::Value>(
      JniDecoder{.env = env, .rt = rt, .object = object, .slotWalk = true}
    );
  }

  facebook::jsi::Value decodeFromJniDynamic(
    JNIEnv* env,
    facebook::jsi::Runtime& rt,
    jobject object
  ) {
    if (object == nullptr) {
      return facebook::jsi::Value::null();
    }

    const CppType kind = JDynamicTypes::kindOf(env, object);
    const DynamicJniDecoder dynamic{.env = env, .rt = rt, .object = object};
    switch (kind) {
      case CppType::STRING: return dynamic.operator()<CppType::STRING>();
      case CppType::LIST: return dynamic.operator()<CppType::LIST>();
      case CppType::MAP: return dynamic.operator()<CppType::MAP>();
      case CppType::RECORD: return dynamic.operator()<CppType::RECORD>();
      case CppType::ANY: throwUnknown(kind);
      default:
        return visitLeafKind<facebook::jsi::Value>(
          kind,
          JniDecoder{.env = env, .rt = rt, .object = object}
        );
    }
  }
} // namespace expo::modules::v2
