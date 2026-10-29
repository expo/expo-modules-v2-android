#include <expo-modules-v2/jni/JSharedObjectRegistry.h>

#include <utility>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/decoders/ModuleDescriptorDecoder.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  int JSharedObjectRegistry::classIdOf(JNIEnv* env, jobject instance) {
    return static_cast<int>(classIdOf_(env, instance));
  }

  void JSharedObjectRegistry::release(JNIEnv* env, jobject instance) {
    release_(env, instance);
  }

  std::optional<JSharedObjectRegistry::ClassExports> JSharedObjectRegistry::encodeClass(
    JNIEnv* env,
    const int classId,
    const jclass declaringClass
  ) {
    const kolibri::Ref<kolibri::JString> jsName = encodeClass_(env, classId);
    if (jsName == nullptr) {
      return std::nullopt;
    }

    const kolibri::binary::BinaryBuffer::Claim claim;
    if (!claim) {
      throwRuntimeError({"The binary bridge buffer is already in use"});
    }

    kolibri::binary::Reader reader = claim.reader();

    descriptor::ModuleDescriptorPayload payload =
      decoders::decodeModuleDescriptorPayload(reader, declaringClass);
    const bool reportsMemoryPressure = reader.readPresence();
    if (!reader.isOnEnd()) {
      throwInvalidArgument({"Trailing bytes in the shared object class metadata payload"});
    }

    return ClassExports{
      .jsName = jsName->toStdString(env),
      .descriptor = std::move(payload),
      .reportsMemoryPressure = reportsMemoryPressure,
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
