package io.github.expo.modules.v2.args

import io.github.expo.modules.v2.types.TypeDescriptor

sealed interface TrampolineArguments {
  fun nextBoolean(): Boolean

  fun nextBooleanOrNull(): Boolean?

  fun nextInt(): Int

  fun nextIntOrNull(): Int?

  fun nextLong(): Long

  fun nextLongOrNull(): Long?

  fun nextFloat(): Float

  fun nextFloatOrNull(): Float?

  fun nextDouble(): Double

  fun nextDoubleOrNull(): Double?

  fun nextString(): String

  fun nextStringOrNull(): String?

  fun nextBooleanArray(): BooleanArray

  fun nextBooleanArrayOrNull(): BooleanArray?

  fun nextIntArray(): IntArray

  fun nextIntArrayOrNull(): IntArray?

  fun nextLongArray(): LongArray

  fun nextLongArrayOrNull(): LongArray?

  fun nextFloatArray(): FloatArray

  fun nextFloatArrayOrNull(): FloatArray?

  fun nextDoubleArray(): DoubleArray

  fun nextDoubleArrayOrNull(): DoubleArray?

  fun nextByteArray(): ByteArray

  fun nextByteArrayOrNull(): ByteArray?

  fun <T> next(type: TypeDescriptor): T

  /**
   * Releases everything this call's arguments still hold. A trampoline must call it from a
   * `finally` around its reads: a failed read leaves the payload half-consumed, and those
   * references must not outlive the call.
   */
  fun finish()
}
