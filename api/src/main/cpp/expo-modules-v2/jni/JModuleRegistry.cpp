#include <expo-modules-v2/jni/JModuleRegistry.h>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/binary/BinaryReader.h>
#include <kolibri/native_method.h>

namespace expo::modules::v2 {
  std::optional<descriptor::ModuleDescriptorPayload> JModuleRegistry::Accessors::encodeModule(
    JNIEnv* env,
    std::string moduleName,
    const ClassOf& classOf
  ) const {
    const kolibri::Ref<> instance = callToken(env, Owner::encodeModule, std::move(moduleName));
    if (instance == nullptr) {
      return std::nullopt;
    }
    const jclass declaringClass = classOf(env, instance.get());

    const kolibri::binary::BinaryBuffer::Claim claim;
    if (!claim) {
      throw std::runtime_error("The binary bridge buffer is already in use");
    }

    kolibri::binary::Reader reader = claim.reader();

    descriptor::ModuleDescriptorPayload descriptor =
      decoders::decodeModuleDescriptorPayload(reader, declaringClass);
    if (!reader.isOnEnd()) {
      throw std::invalid_argument("Trailing bytes in the module metadata payload");
    }

    return descriptor;
  }

  std::vector<std::string> JModuleRegistry::Accessors::encodeModuleNames(JNIEnv* env) const {
    const size_t payloadSize = callToken(env, Owner::encodeModuleNames);

    const kolibri::binary::BinaryBuffer::Claim claim;
    if (!claim) {
      throw std::runtime_error("The binary bridge buffer is already in use");
    }

    kolibri::binary::Reader reader = claim.reader(payloadSize);
    const size_t modulesCount = reader.readCount();

    std::vector<std::string> names;
    names.reserve(modulesCount);

    for (size_t i = 0; i < modulesCount; i++) {
      names.push_back(
        reader.readString()
      );
    }

    if (!reader.isOnEnd()) {
      throw std::invalid_argument("Trailing bytes in the module-name payload");
    }

    return names;
  }
}
