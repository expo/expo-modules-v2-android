package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer

class ListConverter(
  typeDescriptor: TypeDescriptor.Parametrized,
) : TypeConverter<List<*>>(typeDescriptor.isNullable) {
  private val elementDescriptor = typeDescriptor.params[0]

  private val elementConverter: TypeConverter<Any> =
    elementDescriptor.anyConverter

  override val kind: CppType get() = CppType.LIST

  override fun writeTypeParameters(out: TypeCodesWriter) {
    elementConverter.writeTypeCodes(out)
  }

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
          CppType.DOUBLE -> return@writePresent iterateOver<Double>(list) { putDouble(it) }
          CppType.INT -> return@writePresent iterateOver<Int>(list) { putInt(it) }
          CppType.LONG -> return@writePresent iterateOver<Long>(list) { putLong(it) }
          CppType.FLOAT -> return@writePresent iterateOver<Float>(list) { putFloat(it) }
          CppType.BOOLEAN -> return@writePresent iterateOver<Boolean>(list) { putBoolean(it) }
          // Any other element writes itself, one at a time, below.
          else -> Unit
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
        CppType.DOUBLE -> getDoubles(count).toList()
        CppType.INT -> getInts(count).toList()
        CppType.LONG -> getLongs(count).toList()
        CppType.FLOAT -> getFloats(count).toList()
        CppType.BOOLEAN -> {
          val result = ArrayList<Any?>(count)
          repeat(count) {
            result.add(getBoolean())
          }
          result
        }

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
