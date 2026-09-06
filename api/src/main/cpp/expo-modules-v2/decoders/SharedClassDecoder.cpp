#include <expo-modules-v2/decoders/SharedClassDecoder.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

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

    auto constructor = std::make_shared<descriptor::StaticFunctionSpec>(
      descriptor::StaticFunctionSpec{
        descriptor::FunctionSpec{
          .name = jsName,
          .methodName = std::move(trampolineName),
          .argTypes = std::move(argTypes),
          .returnType = ExpectedType::sharedObject(classId, /* nullable */ false),
        },
      }
    );

    descriptor::SharedClassSpec spec;
    spec.jsName = std::move(jsName);
    spec.classId = classId;
    spec.constructor = std::move(constructor);
    return spec;
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
