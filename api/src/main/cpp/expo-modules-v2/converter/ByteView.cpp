#include <expo-modules-v2/converter/ByteView.h>

#include <utility>

#include <expo-modules-v2/jsi/RuntimeGlobals.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  ByteView byteViewOf(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) {
    if (value.isObject()) {
      facebook::jsi::Object object = value.getObject(rt);
      if (object.isArrayBuffer(rt)) {
        const facebook::jsi::ArrayBuffer buffer = std::move(object).getArrayBuffer(rt);
        return {.data = buffer.data(rt), .size = buffer.size(rt)};
      }

      const bool isView = RuntimeGlobals::get(rt)
        .arrayBufferIsView()
        // Reads `value` in place. A plain `1` would pick the overload that clones it.
        .call(rt, &value, size_t{1})
        .getBool();

      if (isView) {
        const facebook::jsi::ArrayBuffer buffer = object
          .getPropertyAsObject(rt, "buffer")
          .getArrayBuffer(rt);

        const size_t offset = static_cast<size_t>(object.getProperty(rt, "byteOffset").getNumber());
        const size_t length = static_cast<size_t>(object.getProperty(rt, "byteLength").getNumber());

        return {.data = buffer.data(rt) + offset, .size = length};
      }
    }

    throwConversionError({
      "Expected an ArrayBuffer, a typed array such as a Uint8Array, or a DataView for a byte array",
    });
  }
}
