package io.github.expo.modules.v2.args

import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import io.github.expo.modules.v2.types.anyConverter

internal class OverflowTrampolineArguments(
  private val slots: Array<Any?>,
) : TrampolineArguments {
  private var cursor = 0

  override fun nextBoolean(): Boolean = take() as Boolean

  override fun nextBooleanOrNull(): Boolean? = take() as Boolean?

  override fun nextInt(): Int = take() as Int

  override fun nextIntOrNull(): Int? = take() as Int?

  override fun nextLong(): Long = take() as Long

  override fun nextLongOrNull(): Long? = take() as Long?

  override fun nextFloat(): Float = take() as Float

  override fun nextFloatOrNull(): Float? = take() as Float?

  override fun nextDouble(): Double = take() as Double

  override fun nextDoubleOrNull(): Double? = take() as Double?

  override fun nextString(): String = take() as String

  override fun nextStringOrNull(): String? = take() as String?

  override fun nextBooleanArray(): BooleanArray = take() as BooleanArray

  override fun nextBooleanArrayOrNull(): BooleanArray? = take() as BooleanArray?

  override fun nextIntArray(): IntArray = take() as IntArray

  override fun nextIntArrayOrNull(): IntArray? = take() as IntArray?

  override fun nextLongArray(): LongArray = take() as LongArray

  override fun nextLongArrayOrNull(): LongArray? = take() as LongArray?

  override fun nextFloatArray(): FloatArray = take() as FloatArray

  override fun nextFloatArrayOrNull(): FloatArray? = take() as FloatArray?

  override fun nextDoubleArray(): DoubleArray = take() as DoubleArray

  override fun nextDoubleArrayOrNull(): DoubleArray? = take() as DoubleArray?

  override fun nextByteArray(): ByteArray = take() as ByteArray

  override fun nextByteArrayOrNull(): ByteArray? = take() as ByteArray?

  @Suppress("UNCHECKED_CAST")
  override fun <T> next(type: TypeDescriptor): T {
    val value = take()

    val converter = type.anyConverter
    if (value != null && converter.isPassthrough) {
      return value as T
    }

    return converter.fromJni(value) as T
  }

  override fun finish() {
    slots.fill(null)
  }

  private fun take(): Any? {
    check(cursor < slots.size) {
      "Trampoline read more than the ${slots.size} available overflow arguments " +
        "(trampoline/declaration drift)"
    }
    val value = slots[cursor]
    slots[cursor] = null
    cursor++
    return value
  }
}
