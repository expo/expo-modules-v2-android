#include <expo-modules-v2/decoders/PropertyDecoder.h>

#include <optional>
#include <string>
#include <utility>
#include <vector>

#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

namespace expo::modules::v2::decoders {
  descriptor::HostPropertySpec decodeHostPropertySpec(
    kolibri::binary::Reader& reader,
    const jclass declaringClass
  ) {
    std::string name = reader.readString();
    std::string getterName = reader.readString();
    ExpectedType getterType = decodeExpectedType(reader, /* allowBufferedHead */ true);

    // The setter carries its own type: a write crosses the other way, so it may ride the buffer
    // while the matching read comes back in a JNI slot.
    std::optional<descriptor::HostFunctionSpec> setter;
    if (reader.readPresence()) {
      std::string setterName = reader.readString();
      std::vector<ExpectedType> argTypes;
      argTypes.push_back(decodeExpectedType(reader, /* allowBufferedHead */ true));
      setter = descriptor::HostFunctionSpec{
        descriptor::FunctionSpec{
          .name = "set " + name,
          .methodName = std::move(setterName),
          .argTypes = std::move(argTypes),
          .returnType = ExpectedType(LeafType::UNIT),
          .declaringClass = declaringClass,
        },
      };
    }

    descriptor::HostFunctionSpec getter{
      descriptor::FunctionSpec{
        .name = "get " + name,
        .methodName = std::move(getterName),
        .argTypes = {},
        .returnType = std::move(getterType),
        .declaringClass = declaringClass,
      },
    };

    return {
      .name = std::move(name),
      .getter = std::move(getter),
      .setter = std::move(setter),
    };
  }

  std::vector<descriptor::HostPropertySpec> decodeHostPropertySpecs(
    kolibri::binary::Reader& reader,
    const jclass declaringClass
  ) {
    const size_t count = reader.readCount();
    std::vector<descriptor::HostPropertySpec> properties;
    properties.reserve(count);
    for (size_t i = 0; i < count; i++) {
      properties.push_back(decodeHostPropertySpec(reader, declaringClass));
    }
    return properties;
  }
} // namespace expo::modules::v2::decoders
