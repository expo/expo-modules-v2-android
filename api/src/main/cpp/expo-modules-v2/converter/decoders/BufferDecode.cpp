#include <expo-modules-v2/converter/decoders/BufferDecode.h>

#include <expo-modules-v2/converter/decoders/BufferDecoder.h>
#include <expo-modules-v2/converter/decoders/DynamicBufferDecoder.h>
#include <expo-modules-v2/converter/TagVisit.h>

namespace expo::modules::v2 {
  using namespace ::expo::kolibri::binary;

  facebook::jsi::Value decodeFromBuffer(
    facebook::jsi::Runtime& rt,
    Reader& in,
    const ExpectedType& type
  ) {
    if (type.nullable() && !in.readPresence()) {
      return facebook::jsi::Value::null();
    }

    return type.visit<facebook::jsi::Value>(
      BufferDecoder{.rt = rt, .in = in}
    );
  }

  facebook::jsi::Value decodeFromBufferDynamic(facebook::jsi::Runtime& rt, Reader& in) {
    return visitTag<facebook::jsi::Value>(
      in.readTag(),
      DynamicBufferDecoder{.rt = rt, .in = in}
    );
  }
} // namespace expo::modules::v2
