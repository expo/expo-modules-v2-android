package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter

abstract class TypeConverter<T>(val isNullable: Boolean) {
  /** The kind this converter crosses as, without flags: the first code [writeTypeCodes] writes. */
  @PublishedApi
  internal abstract val kind: CppType

  /**
   * Writes this converter's type codes into [out]: the head - [kind], marked nullable when it is -
   * then [writeTypeParameters]. Only `UnionConverter` overrides it: its head depends on where it is
   * written, a reference back when the codes are already inside the union, and on whether its
   * variants share one bridge value.
   */
  internal open fun writeTypeCodes(out: TypeCodesWriter) {
    out.add(kind.nullableCode(isNullable))
    writeTypeParameters(out)
  }

  /**
   * What follows the head: the codes of the elements or values a container holds, a record's
   * schema id, a shared object's class id. A converter that holds others writes theirs with their
   * own [writeTypeCodes], through [out], so a union met again inside itself becomes a reference.
   * Nothing, for a leaf.
   */
  internal open fun writeTypeParameters(out: TypeCodesWriter) {}

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
        throw IllegalArgumentException("A non-nullable $kind bridge value arrived as null")
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

/** This converter's type codes with nothing around them: what a top-level type of it sends. */
internal fun TypeConverter<*>.typeCodes(): IntArray =
  TypeCodesWriter().also { writeTypeCodes(it) }.toIntArray()
