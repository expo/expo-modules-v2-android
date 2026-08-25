package expo.modules.v2.converters

import expo.modules.v2.types.CppType
import expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer

class IntArrayConverter(isNullable: Boolean) : TypeConverter<IntArray>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.INT_ARRAY.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = true

  override fun fromJni(value: Any?): IntArray? =
    handleNullable(value) { it as IntArray }

  override fun toJni(value: IntArray?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: IntArray?) =
    buf.writePresent(value) { putIntArray(it) }

  override fun readFromBuffer(buf: BinaryBuffer): IntArray? =
    buf.readPresent { getIntArray() }
}

class LongArrayConverter(isNullable: Boolean) : TypeConverter<LongArray>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.LONG_ARRAY.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = true

  override fun fromJni(value: Any?): LongArray? =
    handleNullable(value) { it as LongArray }

  override fun toJni(value: LongArray?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: LongArray?) =
    buf.writePresent(value) { putLongArray(it) }

  override fun readFromBuffer(buf: BinaryBuffer): LongArray? =
    buf.readPresent { getLongArray() }
}

class FloatArrayConverter(isNullable: Boolean) : TypeConverter<FloatArray>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.FLOAT_ARRAY.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = true

  override fun fromJni(value: Any?): FloatArray? =
    handleNullable(value) { it as FloatArray }

  override fun toJni(value: FloatArray?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: FloatArray?) =
    buf.writePresent(value) { putFloatArray(it) }

  override fun readFromBuffer(buf: BinaryBuffer): FloatArray? =
    buf.readPresent { getFloatArray() }
}

class DoubleArrayConverter(isNullable: Boolean) : TypeConverter<DoubleArray>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.DOUBLE_ARRAY.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = true

  override fun fromJni(value: Any?): DoubleArray? =
    handleNullable(value) { it as DoubleArray }

  override fun toJni(value: DoubleArray?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: DoubleArray?) =
    buf.writePresent(value) { putDoubleArray(it) }

  override fun readFromBuffer(buf: BinaryBuffer): DoubleArray? =
    buf.readPresent { getDoubleArray() }
}

class BooleanArrayConverter(isNullable: Boolean) : TypeConverter<BooleanArray>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.BOOLEAN_ARRAY.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = true

  override fun fromJni(value: Any?): BooleanArray? =
    handleNullable(value) { it as BooleanArray }

  override fun toJni(value: BooleanArray?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: BooleanArray?) =
    buf.writePresent(value) { putBooleanArray(it) }

  override fun readFromBuffer(buf: BinaryBuffer): BooleanArray? =
    buf.readPresent { getBooleanArray() }
}

class ByteArrayConverter(isNullable: Boolean) : TypeConverter<ByteArray>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.BYTE_ARRAY.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = true

  override fun fromJni(value: Any?): ByteArray? =
    handleNullable(value) { it as ByteArray }

  override fun toJni(value: ByteArray?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: ByteArray?) =
    buf.writePresent(value) { putByteArray(it) }

  override fun readFromBuffer(buf: BinaryBuffer): ByteArray? =
    buf.readPresent { getByteArray() }
}
