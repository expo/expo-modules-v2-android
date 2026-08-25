package expo.modules.v2.converters

import expo.modules.v2.types.CppType
import expo.modules.v2.types.TypeCodes
import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer

class ListConverter(
  typeDescriptor: TypeDescriptor.Parametrized,
) : TypeConverter<List<*>>(typeDescriptor.isNullable) {
  private val elementDescriptor = typeDescriptor.params[0]

  private val elementConverter: TypeConverter<Any> =
    elementDescriptor.anyConverter

  override val codes: TypeCodes = CppType.LIST.nullable(isNullable) + elementConverter.codes

  override val isPassthrough: Boolean get() = elementConverter.isPassthrough

  override fun fromJni(value: Any?): List<*>? = handleNullable(value) { bridgeValue ->
    bridgeValue as List<*>

    if (elementConverter.isPassthrough) {
      return@handleNullable bridgeValue
    }

    bridgeValue.map { elementConverter.fromJni(it) }
  }

  override fun toJni(value: List<*>?): Any? {
    if (elementConverter.isPassthrough) {
      return value
    }

    return value?.map {
      elementConverter.toJni(it)
    }
  }

  override fun writeToBuffer(buf: BinaryBuffer, value: List<*>?) =
    buf.writePresent(value) { list ->
      putInt(list.size)
      val fastPath = !elementConverter.isNullable && elementConverter is PassthroughConverter<*>
      if (fastPath) {
        val elementKind = elementConverter.kind
        when (elementKind) {
          CppType.BOX_DOUBLE -> return@writePresent iterateOver<Double>(list) { putDouble(it) }
          CppType.BOX_INT -> return@writePresent iterateOver<Int>(list) { putInt(it) }
          CppType.BOX_LONG -> return@writePresent iterateOver<Long>(list) { putLong(it) }
          CppType.BOX_FLOAT -> return@writePresent iterateOver<Float>(list) { putFloat(it) }
          CppType.BOX_BOOLEAN -> return@writePresent iterateOver<Boolean>(list) { putBoolean(it) }
          CppType.DOUBLE, CppType.INT, CppType.LONG, CppType.FLOAT, CppType.BOOLEAN -> error("List has to use boxed types")
        }
      }

      for (item in list) {
        elementConverter.writeToBuffer(buf, item)
      }
    }

  override fun readFromBuffer(buf: BinaryBuffer): List<*>? = buf.readPresent {
    val count = getInt()

    val fastPath = !elementConverter.isNullable && elementConverter is PassthroughConverter<*>
    if (fastPath) {
      val elementKind = elementConverter.kind
      val converted = when (elementKind) {
        CppType.BOX_DOUBLE -> getDoubles(count).toList()
        CppType.BOX_INT -> getInts(count).toList()
        CppType.BOX_LONG -> getLongs(count).toList()
        CppType.BOX_FLOAT -> getFloats(count).toList()
        CppType.BOX_BOOLEAN -> {
          val result = ArrayList<Any?>(count)
          repeat(count) {
            result.add(getBoolean())
          }
          result
        }

        CppType.DOUBLE, CppType.INT, CppType.LONG, CppType.FLOAT, CppType.BOOLEAN -> error("List has to use boxed types")

        else -> null
      }

      if (converted != null) {
        return@readPresent converted
      }
    }

    val list = ArrayList<Any?>(count)
    repeat(count) {
      list.add(elementConverter.readFromBuffer(buf))
    }

    return@readPresent list
  }

  private inline fun <T> iterateOver(
    values: List<*>,
    putElement: (element: T) -> Unit
  ) {
    @Suppress("UNCHECKED_CAST")
    values as List<T>
    values.forEach(putElement)
  }
}
