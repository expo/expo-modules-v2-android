package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes
import io.github.expo.modules.v2.unions.UnionType
import io.github.expo.modules.v2.unions.UnionValue

/**
 * A union crosses as the index of its variant, then that variant's value: a [UnionValue] on the JNI
 * path, an `Int` followed by the value on the buffer. An `object` variant has no value to follow.
 *
 * Inbound, the index is the variant native code picked by the kind of the JavaScript value. When
 * that variant's conversion refuses the value, [UnionType.decode] hands it to the next one.
 */
internal class UnionConverter(
  private val unionType: UnionType<*>,
  isNullable: Boolean,
) : TypeConverter<Any>(isNullable) {
  override val codes: TypeCodes =
    CppType.UNION.nullable(isNullable) + unionType.id

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Any? =
    handleNullable(value) {
      val transport = it as UnionValue
      unionType.decode(transport.index) { converter -> converter?.fromJni(transport.payload) }
    }

  override fun toJni(value: Any?): Any? =
    value?.let {
      val index = unionType.indexOf(it)
      UnionValue(index, unionType.converterAt(index)?.toJni(unionType.unwrap(it)))
    }

  override fun writeToBuffer(buf: BinaryBuffer, value: Any?) =
    buf.writePresent(value) {
      val index = unionType.indexOf(it)
      putInt(index)
      unionType.converterAt(index)?.writeToBuffer(buf, unionType.unwrap(it))
    }

  override fun readFromBuffer(buf: BinaryBuffer): Any? =
    buf.readPresent {
      val index = getInt()
      val start = position
      unionType.decode(index) { converter ->
        // A variant that refused the value read some of it: the next one starts over.
        position = start
        converter?.readFromBuffer(buf)
      }
    }
}
