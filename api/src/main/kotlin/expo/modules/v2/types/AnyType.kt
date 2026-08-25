package expo.modules.v2.types

import expo.modules.v2.converters.TypeConverter

class AnyType(
  val descriptor: TypeDescriptor,
  val useBuffer: Boolean = false,
) {
  val isNullable: Boolean get() = descriptor.isNullable

  val converter: TypeConverter<*> get() = descriptor.converter

  val anyConverter: TypeConverter<Any> get() = descriptor.anyConverter

  val codes: TypeCodes
    get() {
      val typeCodes = converter.codes
      return if (!useBuffer) {
        typeCodes
      } else {
        typeCodes.withBufferFlag()
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
