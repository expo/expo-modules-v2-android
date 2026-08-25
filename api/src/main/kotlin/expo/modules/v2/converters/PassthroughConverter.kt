package expo.modules.v2.converters

import expo.modules.v2.binary.BinaryValueCodec
import expo.modules.v2.types.CppType
import expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer

/**
 * A leaf whose Kotlin type is its own bridge type: [T] crosses unchanged, so both passes are the
 * value itself and only the buffer encoding is per-kind.
 */
internal sealed class PassthroughConverter<T : Any>(
  val kind: CppType,
  isNullable: Boolean,
) : TypeConverter<T>(isNullable) {
  final override val codes: TypeCodes =
    kind.nullable(isNullable).toTypeCodes()

  final override val isPassthrough: Boolean get() = true

  @Suppress("UNCHECKED_CAST")
  final override fun fromJni(value: Any?): T? =
    handleNullable(value) { it as T }

  final override fun toJni(value: T?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: T?) =
    buf.writePresent(value) { writeValue(buf, it) }

  override fun readFromBuffer(buf: BinaryBuffer): T? =
    buf.readPresent { readValue(buf) }

  protected abstract fun writeValue(buf: BinaryBuffer, value: T)

  protected abstract fun readValue(buf: BinaryBuffer): T?
}

internal sealed class NonNullablePassthroughConverter<T : Any>(
  kind: CppType,
) : PassthroughConverter<T>(kind, isNullable = false)

internal class BooleanConverter : NonNullablePassthroughConverter<Boolean>(CppType.BOOLEAN) {
  override fun writeValue(buf: BinaryBuffer, value: Boolean) {
    buf.putBoolean(value)
  }

  override fun readValue(buf: BinaryBuffer): Boolean = buf.getBoolean()
}

internal class BoxedBooleanConverter(
  isNullable: Boolean,
) : PassthroughConverter<Boolean>(CppType.BOX_BOOLEAN, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: Boolean) {
    buf.putBoolean(value)
  }

  override fun readValue(buf: BinaryBuffer): Boolean = buf.getBoolean()
}

internal class IntConverter : NonNullablePassthroughConverter<Int>(CppType.INT) {
  override fun writeValue(buf: BinaryBuffer, value: Int) {
    buf.putInt(value)
  }

  override fun readValue(buf: BinaryBuffer): Int = buf.getInt()
}

internal class BoxedIntConverter(
  isNullable: Boolean,
) : PassthroughConverter<Int>(CppType.BOX_INT, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: Int) {
    buf.putInt(value)
  }

  override fun readValue(buf: BinaryBuffer): Int = buf.getInt()
}

internal class LongConverter : NonNullablePassthroughConverter<Long>(CppType.LONG) {
  override fun writeValue(buf: BinaryBuffer, value: Long) {
    buf.putLong(value)
  }

  override fun readValue(buf: BinaryBuffer): Long = buf.getLong()
}

internal class BoxedLongConverter(
  isNullable: Boolean,
) : PassthroughConverter<Long>(CppType.BOX_LONG, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: Long) {
    buf.putLong(value)
  }

  override fun readValue(buf: BinaryBuffer): Long = buf.getLong()
}

internal class FloatConverter : NonNullablePassthroughConverter<Float>(CppType.FLOAT) {
  override fun writeValue(buf: BinaryBuffer, value: Float) {
    buf.putFloat(value)
  }

  override fun readValue(buf: BinaryBuffer): Float = buf.getFloat()
}

internal class BoxedFloatConverter(
  isNullable: Boolean,
) : PassthroughConverter<Float>(CppType.BOX_FLOAT, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: Float) {
    buf.putFloat(value)
  }

  override fun readValue(buf: BinaryBuffer): Float = buf.getFloat()
}

internal class DoubleConverter : NonNullablePassthroughConverter<Double>(CppType.DOUBLE) {
  override fun writeValue(buf: BinaryBuffer, value: Double) {
    buf.putDouble(value)
  }

  override fun readValue(buf: BinaryBuffer): Double = buf.getDouble()
}

internal class BoxedDoubleConverter(
  isNullable: Boolean,
) : PassthroughConverter<Double>(CppType.BOX_DOUBLE, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: Double) {
    buf.putDouble(value)
  }

  override fun readValue(buf: BinaryBuffer): Double = buf.getDouble()
}

internal class StringConverter(
  isNullable: Boolean,
) : PassthroughConverter<String>(CppType.STRING, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: String) {
    buf.putString(value)
  }

  override fun readValue(buf: BinaryBuffer): String = buf.getString()
}

internal class UnitConverter(
  isNullable: Boolean,
) : PassthroughConverter<Unit>(CppType.UNIT, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: Unit) = Unit

  override fun readValue(buf: BinaryBuffer): Unit = Unit
}

internal class DynamicConverter(
  isNullable: Boolean,
) : PassthroughConverter<Any>(CppType.ANY, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: Any) {
    BinaryValueCodec.writeDynamic(buf, value)
  }

  override fun readValue(buf: BinaryBuffer): Any? = BinaryValueCodec.readDynamic(buf)
}

internal class JsHandleConverter(
  kind: CppType,
  isNullable: Boolean
) : PassthroughConverter<Any>(kind, isNullable) {
  override fun writeValue(buf: BinaryBuffer, value: Any) {
    error("No typed encoding for $kind")
  }

  override fun readValue(buf: BinaryBuffer): Any? {
    error("No typed decoding for $kind")
  }
}

inline fun <T> selectJsHandleConverter(kind: CppType, crossinline selector: (CppType, Boolean) -> TypeConverter<T>): (Boolean) -> TypeConverter<T> {
  return { isNullable -> selector(kind, isNullable) }
}
