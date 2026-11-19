package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.converters.TypeConverter

class AnyType(
  val descriptor: TypeDescriptor,
  val useBuffer: Boolean = false,
) {
  val isNullable: Boolean get() = descriptor.isNullable

  val converter: TypeConverter<*> get() = descriptor.converter

  val anyConverter: TypeConverter<Any> get() = descriptor.anyConverter

  /** Writes this type's codes into [out], its head marked when it rides the buffer. */
  internal fun writeTypeCodes(out: TypeCodesWriter) {
    val head = out.size
    converter.writeTypeCodes(out)
    if (useBuffer) {
      out.addFlags(head, CppType.USES_BUFFER)
    }
  }

  override fun toString(): String {
    val suffix = if (useBuffer) {
      " (buffered)"
    } else {
      ""
    }

    return descriptor.toString() + suffix
  }
}

fun AnyType.buffered(): AnyType {
  return AnyType(descriptor, useBuffer = true)
}
