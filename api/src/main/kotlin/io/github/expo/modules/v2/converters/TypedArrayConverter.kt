package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.TypedArray
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes

/**
 * A [TypedArray], or one kind of it such as a `Float32Array`, whose bridge is the
 * [JavaScriptObject] it wraps. Like that handle, it is live, so it never rides the buffer.
 */
class TypedArrayConverter(
  private val type: Class<out TypedArray>,
  isNullable: Boolean,
) : TypeConverter<TypedArray>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.JS_OBJECT.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean
    get() = false

  private val expected: String =
    if (type == TypedArray::class.java) {
      "a typed array, such as a Uint8Array, or a DataView"
    } else {
      withArticle(type.simpleName)
    }

  override fun fromJni(value: Any?): TypedArray? =
    handleNullable(value) {
      val jsObject = it as JavaScriptObject
      val kind = jsObject.arrayBufferViewKind()
      val bytes = kind?.let { jsObject.getArrayBufferViewBytes() } ?: throw IllegalArgumentException(
        "Expected $expected, but got " +
          (if (jsObject.isArrayBuffer()) {
            "an ArrayBuffer. Pass a view of the buffer, for example `new " +
              (if (type == TypedArray::class.java) "Uint8Array" else type.simpleName) + "(buffer)`"
          } else {
            "another object"
          }),
      )
      val array = kind.create(jsObject, bytes)
      if (!type.isInstance(array)) {
        throw IllegalArgumentException("Expected $expected, but got ${withArticle(kind.jsName)}")
      }
      array
    }

  override fun toJni(value: TypedArray?): Any? =
    value?.jsObject

  override fun writeToBuffer(buf: BinaryBuffer, value: TypedArray?) =
    error("A TypedArray is a live JavaScript object and cannot be written to the buffer")

  override fun readFromBuffer(buf: BinaryBuffer): TypedArray =
    error("A TypedArray is a live JavaScript object and cannot be read from the buffer")
}

/** "an Int8Array", but "a Uint8Array": `Uint` is read as "you-int". */
private fun withArticle(kindName: String): String =
  if (kindName.startsWith("Int")) "an $kindName" else "a $kindName"
