package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.converters.BooleanArrayConverter
import io.github.expo.modules.v2.converters.BooleanConverter
import io.github.expo.modules.v2.converters.ByteArrayConverter
import io.github.expo.modules.v2.converters.DoubleArrayConverter
import io.github.expo.modules.v2.converters.DoubleConverter
import io.github.expo.modules.v2.converters.FloatArrayConverter
import io.github.expo.modules.v2.converters.FloatConverter
import io.github.expo.modules.v2.converters.IntArrayConverter
import io.github.expo.modules.v2.converters.IntConverter
import io.github.expo.modules.v2.converters.LongArrayConverter
import io.github.expo.modules.v2.converters.LongConverter
import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.converters.selectConverter

sealed interface TypeDescriptor {
  val converter: TypeConverter<*>

  override fun toString(): String

  sealed interface Primitive : TypeDescriptor {
    val cppType: CppType
  }

  object Int : Primitive {
    override val cppType: CppType
      get() = CppType.INT

    override val converter =
      selectConverter(::IntConverter)

    override fun toString(): String = cppType.toString()
  }

  object Bool : Primitive {
    override val cppType: CppType
      get() = CppType.BOOLEAN

    override val converter =
      selectConverter(::BooleanConverter)

    override fun toString(): String = cppType.toString()
  }

  object Long : Primitive {
    override val cppType: CppType
      get() = CppType.LONG

    override val converter =
      selectConverter(::LongConverter)

    override fun toString(): String = cppType.toString()
  }

  object Float : Primitive {
    override val cppType: CppType
      get() = CppType.FLOAT

    override val converter =
      selectConverter(::FloatConverter)

    override fun toString(): String = cppType.toString()
  }

  object Double : Primitive {
    override val cppType: CppType
      get() = CppType.DOUBLE

    override val converter =
      selectConverter(::DoubleConverter)

    override fun toString(): String = cppType.toString()
  }

  sealed interface ObjectLike : TypeDescriptor

  sealed interface PrimitiveArray : ObjectLike {
    val cppType: CppType
    val isNullable: Boolean
  }

  class BooleanArray(override val isNullable: Boolean) : PrimitiveArray {
    override val cppType: CppType get() = CppType.BOOLEAN_ARRAY

    override val converter =
      selectConverter(isNullable, ::BooleanArrayConverter)

    override fun toString(): String = cppType.toString().nullableSuffix(isNullable)
  }

  class IntArray(override val isNullable: Boolean) : PrimitiveArray {
    override val cppType: CppType get() = CppType.INT_ARRAY

    override val converter =
      selectConverter(isNullable, ::IntArrayConverter)

    override fun toString(): String = cppType.toString().nullableSuffix(isNullable)
  }

  class LongArray(override val isNullable: Boolean) : PrimitiveArray {
    override val cppType: CppType get() = CppType.LONG_ARRAY

    override val converter =
      selectConverter(isNullable, ::LongArrayConverter)

    override fun toString(): String = cppType.toString().nullableSuffix(isNullable)
  }

  class FloatArray(override val isNullable: Boolean) : PrimitiveArray {
    override val cppType: CppType get() = CppType.FLOAT_ARRAY

    override val converter =
      selectConverter(isNullable, ::FloatArrayConverter)

    override fun toString(): String = cppType.toString().nullableSuffix(isNullable)
  }

  class DoubleArray(override val isNullable: Boolean) : PrimitiveArray {
    override val cppType: CppType get() = CppType.DOUBLE_ARRAY

    override val converter =
      selectConverter(isNullable, ::DoubleArrayConverter)

    override fun toString(): String = cppType.toString().nullableSuffix(isNullable)
  }

  class ByteArray(override val isNullable: Boolean) : PrimitiveArray {
    override val cppType: CppType get() = CppType.BYTE_ARRAY

    override val converter =
      selectConverter(isNullable, ::ByteArrayConverter)

    override fun toString(): String = cppType.toString().nullableSuffix(isNullable)
  }

  class Simple(
    val javaClass: Class<*>,
    val isNullable: Boolean,
  ) : ObjectLike {
    override val converter: TypeConverter<*> by lazy { TypeConverterRegistry.converter(this) }

    override fun toString(): String = javaClass.simpleName.nullableSuffix(isNullable)

    operator fun component1(): Class<*> = javaClass
    operator fun component2(): Boolean = isNullable
  }

  class Parametrized(
    val javaClass: Class<*>,
    val isNullable: Boolean,
    val params: Array<out ObjectLike>,
  ) : ObjectLike {
    override val converter: TypeConverter<*> by lazy { TypeConverterRegistry.converter(this) }

    override fun toString(): String =
      javaClass.simpleName +
        params.joinToString(", ", "<", ">").nullableSuffix(isNullable)

    operator fun component1(): Class<*> = javaClass
    operator fun component2(): Boolean = isNullable
    operator fun component3(): Array<out ObjectLike> = params
  }
}

private fun String.nullableSuffix(isNullable: Boolean) = this +  if (isNullable) "?" else ""

val TypeDescriptor.isNullable: Boolean
  get() = when (this) {
    is TypeDescriptor.Primitive -> false
    is TypeDescriptor.PrimitiveArray -> isNullable
    is TypeDescriptor.Simple -> isNullable
    is TypeDescriptor.Parametrized -> isNullable
  }

@Suppress("UNCHECKED_CAST")
inline val TypeDescriptor.anyConverter: TypeConverter<Any>
  get() = converter as TypeConverter<Any>
