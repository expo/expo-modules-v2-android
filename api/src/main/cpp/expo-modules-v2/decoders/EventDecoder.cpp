#include <expo-modules-v2/decoders/EventDecoder.h>

#include <string>
#include <utility>

#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

namespace expo::modules::v2::decoders {
  descriptor::EventSpec decodeEventSpec(kolibri::binary::Reader& reader) {
    std::string name = reader.readString();
    ExpectedType payloadType = decodeExpectedType(reader, /* allowBufferedHead */ true);
    return descriptor::EventSpec{
      .name = std::move(name),
      .payloadType = std::move(payloadType),
    };
  }

  std::vector<descriptor::EventSpec> decodeEventSpecs(kolibri::binary::Reader& reader) {
    const size_t count = reader.readCount();
    std::vector<descriptor::EventSpec> events;
    events.reserve(count);
    for (size_t i = 0; i < count; i++) {
      events.push_back(decodeEventSpec(reader));
    }
    return events;
  }
} // namespace expo::modules::v2::decoders
