package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer

class SetConverter<T>(
  typeDescriptor: TypeDescriptor.Parametrized
) : TypeConverter<Set<T>>(typeDescriptor.isNullable) {
  private val elementConverter: TypeConverter<Any> = typeDescriptor.params[0].anyConverter

  override val codes: TypeCodes
    get() = CppType.LIST.nullable(isNullable) + elementConverter.codes

  override val isPassthrough: Boolean get() = false

  @Suppress("UNCHECKED_CAST")
  override fun fromJni(value: Any?): Set<T>? = handleNullable(value) {
    if (elementConverter.isPassthrough) {
      (value as List<T>).toCollection(hashSetOf())
    } else {
      value as List<Any?>
      val result = mutableSetOf<T>()
      for (element in value) {
        result.add(
          elementConverter.fromJni(element) as T
        )
      }
      result
    }
  }

  override fun toJni(value: Set<T>?): Any? =
    if (elementConverter.isPassthrough) {
      value?.toList()
    } else {
      value?.map { element -> elementConverter.toJni(element) }
    }

  override fun writeToBuffer(buf: BinaryBuffer, value: Set<T>?) =
    buf.writePresent(value) { set ->
      buf.putInt(set.size)
      for (element in set) {
        elementConverter.writeToBuffer(buf, element)
      }
    }

  override fun readFromBuffer(buf: BinaryBuffer): Set<T>? = buf.readPresent {
    val count = buf.getInt()
    val set = HashSet<T>(count)
    @Suppress("UNCHECKED_CAST")
    repeat(count) { set.add(elementConverter.readFromBuffer(buf) as T) }
    set
  }
}
