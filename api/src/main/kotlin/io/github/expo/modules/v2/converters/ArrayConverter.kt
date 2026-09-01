package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer

class ArrayConverter<T>(
  typeDescriptor: TypeDescriptor.Parametrized
) : TypeConverter<Array<T>>(typeDescriptor.isNullable) {
  private val elementConverter: TypeConverter<Any> =
    typeDescriptor.params[0].anyConverter

  private val elementClass: Class<*> =
    requireNotNull(typeDescriptor.javaClass.componentType?.takeIf { !it.isPrimitive }) {
      "${typeDescriptor.javaClass.name} is not an object-array class, so it declares no element type"
    }

  override val codes: TypeCodes
    get() = CppType.LIST.nullable(isNullable) + elementConverter.codes

  override val isPassthrough: Boolean get() = false

  @Suppress("UNCHECKED_CAST")
  override fun fromJni(value: Any?): Array<T>? = handleNullable(value) {
    it as List<Any?>

    val result = newArray(it.size)
    if (elementConverter.isPassthrough) {
      for ((index, element) in it.withIndex()) {
        result[index] = element as T
      }
    } else {
      for ((index, element) in it.withIndex()) {
        result[index] = elementConverter.fromJni(element) as T
      }
    }

    result
  }

  override fun toJni(value: Array<T>?): Any? {
    if (elementConverter.isPassthrough) {
      return value?.toList()
    }

    return value?.map {
      elementConverter.toJni(it)
    }
  }

  override fun writeToBuffer(buf: BinaryBuffer, value: Array<T>?) =
    buf.writePresent(value) { array ->
      buf.putInt(array.size)
      for (element in array) {
        elementConverter.writeToBuffer(buf, element)
      }
    }

  @Suppress("UNCHECKED_CAST")
  override fun readFromBuffer(buf: BinaryBuffer): Array<T>? =
    buf.readPresent {
      val count = buf.getInt()

      val result = newArray(count)
      for (index in 0 until count) {
        result[index] = elementConverter.readFromBuffer(buf) as T
      }

      result
    }

  @Suppress("UNCHECKED_CAST")
  private fun newArray(size: Int): Array<T> =
    java.lang.reflect.Array.newInstance(elementClass, size) as Array<T>
}
