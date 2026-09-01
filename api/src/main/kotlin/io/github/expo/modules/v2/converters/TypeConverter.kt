package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer

abstract class TypeConverter<T>(val isNullable: Boolean) {
  abstract val codes: TypeCodes

  abstract val isPassthrough: Boolean

  abstract fun fromJni(value: Any?): T?

  abstract fun toJni(value: T?): Any?

  abstract fun writeToBuffer(buf: BinaryBuffer, value: T?)

  abstract fun readFromBuffer(buf: BinaryBuffer): T?

  inline fun <T> BinaryBuffer.readPresent(readPayload: BinaryBuffer.() -> T): T? {
    if (isNullable && !getBoolean()) {
      return null
    }

    return readPayload()
  }

  inline fun <T : Any> BinaryBuffer.writePresent(
    value: T?,
    writePayload: BinaryBuffer.(T) -> Unit,
  ) {
    if (isNullable) {
      putBoolean(value != null)
      if (value == null) return
    } else if (value == null) {
      throw IllegalArgumentException("Cannot encode null into a non-nullable slot")
    }
    writePayload(value)
  }

  inline fun <T> handleNullable(value: Any?, convert: (Any) -> T?): T? {
    if (value == null) {
      if (!isNullable) {
        throw IllegalArgumentException("A non-nullable ${codes.head} bridge value arrived as null")
      }
      return null
    }

    return convert(value)
  }
}

inline fun <T> selectConverter(selector: () -> TypeConverter<T>): TypeConverter<T> {
  return selector()
}

inline fun <T> selectConverter(isNullable: Boolean, selector: (Boolean) -> TypeConverter<T>): TypeConverter<T> {
  return selector(isNullable)
}
