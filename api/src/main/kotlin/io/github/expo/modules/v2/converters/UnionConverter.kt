package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import io.github.expo.modules.v2.unions.UnionType
import io.github.expo.modules.v2.unions.UnionValue

/**
 * A union crosses as the index of its variant, then that variant's value: a [UnionValue] on the JNI
 * path, an `Int` followed by the value on the buffer. An `object` variant has no value to follow.
 * Inbound, the index is the variant native code picked by the kind of the JavaScript value. When
 * that variant's conversion refuses the value, [UnionType.decode] hands it to the next one.
 */
internal class UnionConverter(
  private val unionType: UnionType<*>,
  isNullable: Boolean,
) : TypeConverter<Any>(isNullable) {
  /** A union whose variants share a bridge value crosses as that value, with no variant index. */
  private val sameBridge = unionType.schema.sameBridge

  override val kind: CppType
    get() = if (sameBridge) {
      requireNotNull(unionType.converterAt(0)).kind
    } else {
      CppType.UNION
    }

  /** The union spelled out, or a [CppType.UNION_REF] back to it when the codes are inside it. */
  override fun writeTypeCodes(out: TypeCodesWriter) {
    val depth = out.depthOf(unionType)
    if (depth >= 0) {
      out.add(CppType.UNION_REF.nullableCode(isNullable))
      out.add(depth)
      return
    }
    unionType.writeTypeCodes(out, isNullable)
  }

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Any? =
    handleNullable(value) {
      if (sameBridge) {
        return@handleNullable unionType.decode(0) { converter -> converter?.fromJni(it) }
      }
      val transport = it as UnionValue
      unionType.decode(transport.index) { converter -> converter?.fromJni(transport.payload) }
    }

  override fun toJni(value: Any?): Any? =
    value?.let {
      val index = unionType.indexOf(it)
      val payload = unionType.converterAt(index)?.toJni(unionType.unwrap(it))
      if (sameBridge) payload else UnionValue(index, payload)
    }

  override fun writeToBuffer(buf: BinaryBuffer, value: Any?) =
    buf.writePresent(value) {
      val index = unionType.indexOf(it)
      if (!sameBridge) {
        putInt(index)
      }
      unionType.converterAt(index)?.writeToBuffer(buf, unionType.unwrap(it))
    }

  override fun readFromBuffer(buf: BinaryBuffer): Any? =
    buf.readPresent {
      val index = if (sameBridge) 0 else getInt()
      val start = position
      unionType.decode(index) { converter ->
        // A variant that refused the value read some of it: the next one starts over.
        position = start
        converter?.readFromBuffer(buf)
      }
    }
}
