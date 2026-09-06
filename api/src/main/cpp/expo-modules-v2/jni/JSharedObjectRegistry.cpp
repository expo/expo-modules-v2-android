#include <expo-modules-v2/jni/JSharedObjectRegistry.h>

#include <utility>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/decoders/ModuleDescriptorDecoder.h>

namespace expo::modules::v2 {
  JSharedObjectRegistry::Attachment::Attachment(jlong packed)
    : classId(static_cast<int>(static_cast<uint64_t>(packed) >> 32)),
      objectId(static_cast<int>(static_cast<uint32_t>(packed))) {
  }

  JSharedObjectRegistry::Attachment JSharedObjectRegistry::attach(JNIEnv* env, jobject instance) {
    const jlong packed = attach_(env, instance);
    return Attachment(packed);
  }

  void JSharedObjectRegistry::release(JNIEnv* env, jobject instance) {
    release_(env, instance);
  }

  std::optional<JSharedObjectRegistry::ClassExports> JSharedObjectRegistry::encodeClass(
    JNIEnv* env,
    const int classId
  ) {
    const kolibri::Ref<kolibri::JString> jsName = encodeClass_(env, classId);
    if (jsName == nullptr) {
      return std::nullopt;
    }

    const kolibri::binary::BinaryBuffer::Claim claim;
    if (!claim) {
      throw std::runtime_error("The binary bridge buffer is already in use");
    }

    kolibri::binary::Reader reader = claim.reader();

    descriptor::ModuleDescriptorPayload payload = decoders::decodeModuleDescriptorPayload(reader);
    if (!reader.isOnEnd()) {
      throw std::invalid_argument("Trailing bytes in the shared object class metadata payload");
    }

    return ClassExports{
      .jsName = jsName->toStdString(env),
      .descriptor = std::move(payload),
    };
  }

  kolibri::Ref<kolibri::JClass> JSharedObjectRegistry::sharedClassOf(
    JNIEnv* env,
    const int classId
  ) {
    return sharedClassOf_(env, classId);
  }

  std::optional<std::string> JSharedObjectRegistry::classDescriptorOf(
    JNIEnv* env,
    const int classId
  ) {
    const kolibri::Ref<kolibri::JString> descriptor =
      classDescriptorOf_(env, classId);
    if (descriptor == nullptr) {
      return std::nullopt;
    }
    return descriptor->toStdString(env);
  }
} // namespace expo::modules::v2
