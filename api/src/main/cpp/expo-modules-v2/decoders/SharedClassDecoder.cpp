#include <expo-modules-v2/decoders/SharedClassDecoder.h>

#include <stdexcept>
#include <string>
#include <utility>

#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>

namespace expo::modules::v2::decoders {
  descriptor::SharedClassSpec decodeSharedClassSpec(kolibri::binary::Reader& reader) {
    std::string jsName = reader.readString();
    const auto classId = reader.read<int32_t>();

    std::string trampolineName = reader.readString();
    if (trampolineName.empty()) {
      throw std::invalid_argument(
        "Constructable shared class " + jsName + " declares no constructor trampoline"
      );
    }

    const size_t argsCount = reader.readCount();
    if (argsCount > descriptor::FunctionSpec::kMaxArgs) {
      throw std::invalid_argument(
        "Constructor of " + jsName + " declares " + std::to_string(argsCount) +
        " arguments; at most " +
        std::to_string(descriptor::FunctionSpec::kMaxArgs) + " are supported"
      );
    }

    std::vector<ExpectedType> argTypes;
    argTypes.reserve(argsCount);
    for (size_t i = 0; i < argsCount; i++) {
      argTypes.push_back(decodeExpectedType(reader, /* allowBufferedHead */ true));
    }

    const jclass sharedClass = sharedobjects::SharedObjectClassRegistry::javaClassOf(classId);
    if (sharedClass == nullptr) {
      throw std::invalid_argument(
        "Shared class " + jsName + " (id " + std::to_string(classId) + ") is not registered"
      );
    }

    descriptor::StaticFunctionSpec constructor{
      descriptor::FunctionSpec{
        .name = jsName,
        .methodName = std::move(trampolineName),
        .argTypes = std::move(argTypes),
        .returnType = ExpectedType::sharedObject(classId, /* nullable */ false),
        .declaringClass = sharedClass,
      },
    };

    return descriptor::SharedClassSpec{
      .jsName = std::move(jsName),
      .classId = classId,
      .constructor = std::move(constructor),
    };
  }

  std::vector<descriptor::SharedClassSpec> decodeSharedClassSpecs(
    kolibri::binary::Reader& reader
  ) {
    const size_t count = reader.readCount();
    std::vector<descriptor::SharedClassSpec> sharedClasses;
    sharedClasses.reserve(count);
    for (size_t i = 0; i < count; i++) {
      sharedClasses.push_back(decodeSharedClassSpec(reader));
    }
    return sharedClasses;
  }
} // namespace expo::modules::v2::decoders
