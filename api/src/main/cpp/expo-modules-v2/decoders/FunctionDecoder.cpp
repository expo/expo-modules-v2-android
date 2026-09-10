#include <expo-modules-v2/decoders/FunctionDecoder.h>

#include <stdexcept>
#include <string>
#include <utility>

#include <expo-modules-v2/descriptor/HostFunctionSpec.h>
#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

namespace expo::modules::v2::decoders {
  descriptor::HostFunctionSpec decodeHostFunctionSpec(
    kolibri::binary::Reader& reader,
    const jclass declaringClass
  ) {
    std::string name = reader.readString();
    std::string methodName = reader.readString();
    const int32_t flags = reader.read<int32_t>();
    const bool async = (flags & descriptor::kFunctionFlagAsync) != 0;

    const size_t argsCount = reader.readCount();
    if (argsCount > descriptor::FunctionSpec::kMaxArgs) {
      throw std::invalid_argument(
        "Function " + name + " declares " + std::to_string(argsCount) +
        " arguments; at most " +
        std::to_string(descriptor::FunctionSpec::kMaxArgs) + " are supported"
      );
    }
    std::vector<ExpectedType> argTypes;
    argTypes.reserve(argsCount);
    for (size_t i = 0; i < argsCount; i++) {
      argTypes.push_back(decodeExpectedType(reader, /* allowBufferedHead */ true));
    }

    ExpectedType returnType = decodeExpectedType(reader, /* allowBufferedHead */ true);

    return descriptor::HostFunctionSpec{
      descriptor::FunctionSpec{
        .name = std::move(name),
        .methodName = std::move(methodName),
        .argTypes = std::move(argTypes),
        .returnType = std::move(returnType),
        .async = async,
        .declaringClass = declaringClass,
      },
    };
  }

  std::vector<descriptor::HostFunctionSpec> decodeHostFunctionSpecs(
    kolibri::binary::Reader& reader,
    const jclass declaringClass
  ) {
    const size_t count = reader.readCount();
    std::vector<descriptor::HostFunctionSpec> functions;
    functions.reserve(count);
    for (size_t i = 0; i < count; i++) {
      functions.push_back(decodeHostFunctionSpec(reader, declaringClass));
    }
    return functions;
  }
} // namespace expo::modules::v2::decoders
