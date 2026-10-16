#include <expo-modules-cpp/TypedArray.h>

namespace expo::modules::v2::cpp {
  TypedArray Converter<TypedArray>::fromJS(js::Runtime& rt, const js::Value& value) {
    if (value.isObject()) {
      js::Object object = value.getObject(rt);
      if (object.isArrayBuffer(rt)) {
        detail::throwConversion(
          "expected a typed array, such as a Uint8Array, or a DataView, got an ArrayBuffer. "
          "Pass a view of the buffer, for example `new Uint8Array(buffer)`"
        );
      }

      const bool isView = rt.global()
        .getPropertyAsObject(rt, "ArrayBuffer")
        .getPropertyAsFunction(rt, "isView")
        .call(rt, value)
        .getBool();
      if (isView) {
        const detail::ByteView bytes = detail::byteViewOfView(rt, object);
        return TypedArray(std::move(object), bytes.data, bytes.size);
      }
    }

    detail::throwExpected(rt, "a typed array, such as a Uint8Array, or a DataView", value);
  }
} // namespace expo::modules::v2::cpp
