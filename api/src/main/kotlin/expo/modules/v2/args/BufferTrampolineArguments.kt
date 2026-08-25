package expo.modules.v2.args

import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter
import expo.modules.v2.binary.rewind
import io.github.expo.kolibri.binary.BinaryBuffer

internal class BufferTrampolineArguments(
  private val buf: BinaryBuffer,
) : TrampolineArguments {
  fun rewind(payloadLength: Int) {
    buf.rewind(payloadLength)
  }

  override fun nextBoolean(): Boolean = buf.getBoolean()

  override fun nextBooleanOrNull(): Boolean? =
    if (buf.getBoolean()) {
      buf.getBoolean()
    } else {
      null
    }

  override fun nextInt(): Int = buf.getInt()

  override fun nextIntOrNull(): Int? =
    if (buf.getBoolean()) {
      buf.getInt()
    } else {
      null
    }

  override fun nextLong(): Long = buf.getLong()

  override fun nextLongOrNull(): Long? =
    if (buf.getBoolean()) {
      buf.getLong()
    } else {
      null
    }

  override fun nextFloat(): Float = buf.getFloat()

  override fun nextFloatOrNull(): Float? =
    if (buf.getBoolean()) {
      buf.getFloat()
    } else {
      null
    }

  override fun nextDouble(): Double = buf.getDouble()

  override fun nextDoubleOrNull(): Double? =
    if (buf.getBoolean()) {
      buf.getDouble()
    } else {
      null
    }

  override fun nextString(): String = buf.getString()

  override fun nextStringOrNull(): String? =
    if (buf.getBoolean()) {
      buf.getString()
    } else {
      null
    }

  override fun nextBooleanArray(): BooleanArray = buf.getBooleanArray()

  override fun nextBooleanArrayOrNull(): BooleanArray? =
    if (buf.getBoolean()) {
      buf.getBooleanArray()
    } else {
      null
    }

  override fun nextIntArray(): IntArray = buf.getIntArray()

  override fun nextIntArrayOrNull(): IntArray? =
    if (buf.getBoolean()) {
      buf.getIntArray()
    } else {
      null
    }

  override fun nextLongArray(): LongArray = buf.getLongArray()

  override fun nextLongArrayOrNull(): LongArray? =
    if (buf.getBoolean()) {
      buf.getLongArray()
    } else {
      null
    }

  override fun nextFloatArray(): FloatArray = buf.getFloatArray()

  override fun nextFloatArrayOrNull(): FloatArray? =
    if (buf.getBoolean()) {
      buf.getFloatArray()
    } else {
      null
    }

  override fun nextDoubleArray(): DoubleArray = buf.getDoubleArray()

  override fun nextDoubleArrayOrNull(): DoubleArray? =
    if (buf.getBoolean()) {
      buf.getDoubleArray()
    } else {
      null
    }

  override fun nextByteArray(): ByteArray =
    buf.getByteArray()

  override fun nextByteArrayOrNull(): ByteArray? =
    if (buf.getBoolean()) {
      buf.getByteArray()
    } else {
      null
    }

  @Suppress("UNCHECKED_CAST")
  override fun <T> next(type: TypeDescriptor): T {
    return type.anyConverter.readFromBuffer(buf) as T
  }

  override fun finish() = Unit
}
