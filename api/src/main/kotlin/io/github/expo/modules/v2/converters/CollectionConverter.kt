package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import io.github.expo.modules.v2.types.TypeDescriptor

/**
 * A `Collection<T>`, which crosses as a JavaScript array: the same bridge value as a `List<T>`.
 *
 * Coming in, the array becomes a `List`, which is a `Collection`. Going out, any collection - a
 * `Set`, a map's `values`, an `ArrayDeque` - is written in its iteration order. It is never a
 * passthrough: only a `List` is the bridge's own type, so anything else is copied into one.
 */
class CollectionConverter(
  typeDescriptor: TypeDescriptor.Parametrized,
) : TypeConverter<Collection<*>>(typeDescriptor.isNullable) {
  private val list = ListConverter(typeDescriptor)

  override val kind: CppType get() = CppType.LIST

  override fun writeTypeParameters(out: TypeCodesWriter) {
    list.writeTypeParameters(out)
  }

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Collection<*>? = list.fromJni(value)

  override fun toJni(value: Collection<*>?): Any? = list.toJni(value?.asList())

  override fun writeToBuffer(buf: BinaryBuffer, value: Collection<*>?) =
    list.writeToBuffer(buf, value?.asList())

  override fun readFromBuffer(buf: BinaryBuffer): Collection<*>? = list.readFromBuffer(buf)

  private fun Collection<*>.asList(): List<*> = this as? List<*> ?: toList()
}
