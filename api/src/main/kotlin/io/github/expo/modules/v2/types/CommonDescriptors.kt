package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.converters.boxedBooleanConverter
import io.github.expo.modules.v2.converters.boxedDoubleConverter
import io.github.expo.modules.v2.converters.boxedFloatConverter
import io.github.expo.modules.v2.converters.boxedIntConverter
import io.github.expo.modules.v2.converters.boxedLongConverter
import io.github.expo.modules.v2.converters.dynamicConverter
import io.github.expo.modules.v2.converters.stringConverter

/**
 * The [TypeDescriptor.Simple] instances the record compiler plugin emits most often, as static
 * fields it can read with a single `getstatic`.
 *
 * Boxing is the reason this exists. `Int::class.java` is `int.class` while `Int::class.javaObjectType`
 * is `Integer.class`, and [TypeConverterRegistry] keys its cache on the boxed class - emitting the
 * right class literal for a boxed primitive from IR is easy to get subtly wrong. Reading a field is
 * not. It also saves one allocation per descriptor.
 */
object CommonDescriptors {
  @JvmField
  val BOOLEAN_BOXED = TypeDescriptor.Simple(
    Boolean::class.javaObjectType,
    false,
    boxedBooleanConverter(false)
  )

  @JvmField
  val BOOLEAN_BOXED_NULL = TypeDescriptor.Simple(
    Boolean::class.javaObjectType,
    true,
    boxedBooleanConverter(true)
  )

  @JvmField
  val INT_BOXED = TypeDescriptor.Simple(
    Int::class.javaObjectType,
    false,
    boxedIntConverter(false)
  )

  @JvmField
  val INT_BOXED_NULL = TypeDescriptor.Simple(
    Int::class.javaObjectType,
    true,
    boxedIntConverter(true)
  )

  @JvmField
  val LONG_BOXED = TypeDescriptor.Simple(
    Long::class.javaObjectType,
    false,
    boxedLongConverter(false)
  )

  @JvmField
  val LONG_BOXED_NULL = TypeDescriptor.Simple(
    Long::class.javaObjectType,
    true,
    boxedLongConverter(true)
  )

  @JvmField
  val FLOAT_BOXED = TypeDescriptor.Simple(
    Float::class.javaObjectType,
    false,
    boxedFloatConverter(false)
  )

  @JvmField
  val FLOAT_BOXED_NULL = TypeDescriptor.Simple(
    Float::class.javaObjectType,
    true,
    boxedFloatConverter(true)
  )

  @JvmField
  val DOUBLE_BOXED = TypeDescriptor.Simple(
    Double::class.javaObjectType,
    false,
    boxedDoubleConverter(false)
  )

  @JvmField
  val DOUBLE_BOXED_NULL = TypeDescriptor.Simple(
    Double::class.javaObjectType,
    true,
    boxedDoubleConverter(true)
  )

  @JvmField
  val STRING = TypeDescriptor.Simple(
    String::class.java,
    false,
    stringConverter(false)
  )

  @JvmField
  val STRING_NULL = TypeDescriptor.Simple(
    String::class.java,
    true,
    stringConverter(true)
  )

  @JvmField
  val ANY = TypeDescriptor.Simple(
    Any::class.java,
    false,
    dynamicConverter(false)
  )

  @JvmField
  val ANY_NULL = TypeDescriptor.Simple(
    Any::class.java,
    true,
    dynamicConverter(true)
  )
}
