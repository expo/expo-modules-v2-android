package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.TypedArray
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes

/**
 * A [TypedArray], whose bridge is the [JavaScriptObject] it wraps. Like that handle, it is live, so
 * it never rides the buffer.
 */
class TypedArrayConverter(isNullable: Boolean) : TypeConverter<TypedArray>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.JS_OBJECT.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean
    get() = false

  override fun fromJni(value: Any?): TypedArray? =
    handleNullable(value) {
      val jsObject = it as JavaScriptObject
      val bytes = jsObject.getArrayBufferViewBytes() ?: throw IllegalArgumentException(
        "Expected a typed array, such as a Uint8Array, or a DataView, but got " +
          (if (jsObject.isArrayBuffer()) {
            "an ArrayBuffer"
          } else {
            "another object"
          }) +
          ". Pass a view of the buffer, for example `new Uint8Array(buffer)`",
      )
      TypedArray(jsObject, bytes)
    }

  override fun toJni(value: TypedArray?): Any? =
    value?.jsObject

  override fun writeToBuffer(buf: BinaryBuffer, value: TypedArray?) =
    error("A TypedArray is a live JavaScript object and cannot be written to the buffer")

  override fun readFromBuffer(buf: BinaryBuffer): TypedArray =
    error("A TypedArray is a live JavaScript object and cannot be read from the buffer")
}
