package io.github.expo.modules.v2

import io.github.expo.modules.v2.jsi.JavaScriptObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer

// Each kind reads its elements in native byte order, as JavaScript lays them out. An index out of
// range throws IndexOutOfBoundsException, from the buffer the elements are read through.
//
// An unsigned kind reads and writes Kotlin's unsigned type one element at a time. Its bulk members
// work with the signed array of the same width, which holds the same bits.

private fun elementsOf(bytes: ByteBuffer): ByteBuffer = bytes.duplicate().order(ByteOrder.nativeOrder())

/** A JavaScript `Int8Array`, of [Byte] elements. */
class Int8Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<Byte> {
  private val elements = elementsOf(bytes)

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): Byte = elements.get(index)

  operator fun set(index: Int, value: Byte) {
    elements.put(index, value)
  }

  /** Sets every element to [value]. */
  fun fill(value: Byte) {
    for (index in indices) elements.put(index, value)
  }

  /** A copy of the elements. */
  fun toByteArray(): ByteArray = ByteArray(size).also { toDirectBuffer().get(it) }

  /** Copies all of [source] into this array, starting at element [offset]. */
  fun copyFrom(source: ByteArray, offset: Int = 0) {
    toDirectBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): ByteIterator = object : ByteIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun nextByte(): Byte = get(index++)
  }
}

/** A JavaScript `Uint8Array`, of [UByte] elements. */
class Uint8Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<UByte> {
  private val elements = elementsOf(bytes)

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): UByte = elements.get(index).toUByte()

  operator fun set(index: Int, value: UByte) {
    elements.put(index, value.toByte())
  }

  /** Sets every element to [value]. */
  fun fill(value: UByte) {
    for (index in indices) elements.put(index, value.toByte())
  }

  /** A copy of the elements' bits. */
  fun toByteArray(): ByteArray = ByteArray(size).also { toDirectBuffer().get(it) }

  /** Copies all of [source]'s bits into this array, starting at element [offset]. */
  fun copyFrom(source: ByteArray, offset: Int = 0) {
    toDirectBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): Iterator<UByte> = object : Iterator<UByte> {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun next(): UByte = get(index++)
  }
}

/**
 * A JavaScript `Uint8ClampedArray`, of [UByte] elements. Every [UByte] is in range, so a write from
 * Kotlin has nothing to clamp.
 */
class Uint8ClampedArray internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<UByte> {
  private val elements = elementsOf(bytes)

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): UByte = elements.get(index).toUByte()

  operator fun set(index: Int, value: UByte) {
    elements.put(index, value.toByte())
  }

  /** Sets every element to [value]. */
  fun fill(value: UByte) {
    for (index in indices) elements.put(index, value.toByte())
  }

  /** A copy of the elements' bits. */
  fun toByteArray(): ByteArray = ByteArray(size).also { toDirectBuffer().get(it) }

  /** Copies all of [source]'s bits into this array, starting at element [offset]. */
  fun copyFrom(source: ByteArray, offset: Int = 0) {
    toDirectBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): Iterator<UByte> = object : Iterator<UByte> {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun next(): UByte = get(index++)
  }
}

/** A JavaScript `Int16Array`, of [Short] elements. */
class Int16Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<Short> {
  private val elements: ShortBuffer = elementsOf(bytes).asShortBuffer()

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): Short = elements.get(index)

  operator fun set(index: Int, value: Short) {
    elements.put(index, value)
  }

  /** Sets every element to [value]. */
  fun fill(value: Short) {
    for (index in indices) elements.put(index, value)
  }

  /**
   * A buffer over the elements, without a copy, positioned at the first one. Each call returns a
   * new buffer, so their positions are independent.
   */
  fun asShortBuffer(): ShortBuffer = elements.duplicate()

  /** A copy of the elements. */
  fun toShortArray(): ShortArray = ShortArray(size).also { asShortBuffer().get(it) }

  /** Copies all of [source] into this array, starting at element [offset]. */
  fun copyFrom(source: ShortArray, offset: Int = 0) {
    asShortBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): ShortIterator = object : ShortIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun nextShort(): Short = get(index++)
  }
}

/** A JavaScript `Uint16Array`, of [UShort] elements. */
class Uint16Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<UShort> {
  private val elements: ShortBuffer = elementsOf(bytes).asShortBuffer()

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): UShort = elements.get(index).toUShort()

  operator fun set(index: Int, value: UShort) {
    elements.put(index, value.toShort())
  }

  /** Sets every element to [value]. */
  fun fill(value: UShort) {
    for (index in indices) elements.put(index, value.toShort())
  }

  /**
   * A buffer over the elements' bits, without a copy, positioned at the first one. Each call
   * returns a new buffer, so their positions are independent.
   */
  fun asShortBuffer(): ShortBuffer = elements.duplicate()

  /** A copy of the elements' bits. */
  fun toShortArray(): ShortArray = ShortArray(size).also { asShortBuffer().get(it) }

  /** Copies all of [source]'s bits into this array, starting at element [offset]. */
  fun copyFrom(source: ShortArray, offset: Int = 0) {
    asShortBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): Iterator<UShort> = object : Iterator<UShort> {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun next(): UShort = get(index++)
  }
}

/** A JavaScript `Int32Array`, of [Int] elements. */
class Int32Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<Int> {
  private val elements: IntBuffer = elementsOf(bytes).asIntBuffer()

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): Int = elements.get(index)

  operator fun set(index: Int, value: Int) {
    elements.put(index, value)
  }

  /** Sets every element to [value]. */
  fun fill(value: Int) {
    for (index in indices) elements.put(index, value)
  }

  /**
   * A buffer over the elements, without a copy, positioned at the first one. Each call returns a
   * new buffer, so their positions are independent.
   */
  fun asIntBuffer(): IntBuffer = elements.duplicate()

  /** A copy of the elements. */
  fun toIntArray(): IntArray = IntArray(size).also { asIntBuffer().get(it) }

  /** Copies all of [source] into this array, starting at element [offset]. */
  fun copyFrom(source: IntArray, offset: Int = 0) {
    asIntBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): IntIterator = object : IntIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun nextInt(): Int = get(index++)
  }
}

/** A JavaScript `Uint32Array`, of [UInt] elements. */
class Uint32Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<UInt> {
  private val elements: IntBuffer = elementsOf(bytes).asIntBuffer()

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): UInt = elements.get(index).toUInt()

  operator fun set(index: Int, value: UInt) {
    elements.put(index, value.toInt())
  }

  /** Sets every element to [value]. */
  fun fill(value: UInt) {
    for (index in indices) elements.put(index, value.toInt())
  }

  /**
   * A buffer over the elements' bits, without a copy, positioned at the first one. Each call
   * returns a new buffer, so their positions are independent.
   */
  fun asIntBuffer(): IntBuffer = elements.duplicate()

  /** A copy of the elements' bits. */
  fun toIntArray(): IntArray = IntArray(size).also { asIntBuffer().get(it) }

  /** Copies all of [source]'s bits into this array, starting at element [offset]. */
  fun copyFrom(source: IntArray, offset: Int = 0) {
    asIntBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): Iterator<UInt> = object : Iterator<UInt> {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun next(): UInt = get(index++)
  }
}

/** A JavaScript `Float32Array`, of [Float] elements. */
class Float32Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<Float> {
  private val elements: FloatBuffer = elementsOf(bytes).asFloatBuffer()

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): Float = elements.get(index)

  operator fun set(index: Int, value: Float) {
    elements.put(index, value)
  }

  /** Sets every element to [value]. */
  fun fill(value: Float) {
    for (index in indices) elements.put(index, value)
  }

  /**
   * A buffer over the elements, without a copy, positioned at the first one. Each call returns a
   * new buffer, so their positions are independent.
   */
  fun asFloatBuffer(): FloatBuffer = elements.duplicate()

  /** A copy of the elements. */
  fun toFloatArray(): FloatArray = FloatArray(size).also { asFloatBuffer().get(it) }

  /** Copies all of [source] into this array, starting at element [offset]. */
  fun copyFrom(source: FloatArray, offset: Int = 0) {
    asFloatBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): FloatIterator = object : FloatIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun nextFloat(): Float = get(index++)
  }
}

/** A JavaScript `Float64Array`, of [Double] elements. */
class Float64Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<Double> {
  private val elements: DoubleBuffer = elementsOf(bytes).asDoubleBuffer()

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): Double = elements.get(index)

  operator fun set(index: Int, value: Double) {
    elements.put(index, value)
  }

  /** Sets every element to [value]. */
  fun fill(value: Double) {
    for (index in indices) elements.put(index, value)
  }

  /**
   * A buffer over the elements, without a copy, positioned at the first one. Each call returns a
   * new buffer, so their positions are independent.
   */
  fun asDoubleBuffer(): DoubleBuffer = elements.duplicate()

  /** A copy of the elements. */
  fun toDoubleArray(): DoubleArray = DoubleArray(size).also { asDoubleBuffer().get(it) }

  /** Copies all of [source] into this array, starting at element [offset]. */
  fun copyFrom(source: DoubleArray, offset: Int = 0) {
    asDoubleBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): DoubleIterator = object : DoubleIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun nextDouble(): Double = get(index++)
  }
}

/** A JavaScript `BigInt64Array`, of [Long] elements. */
class BigInt64Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<Long> {
  private val elements: LongBuffer = elementsOf(bytes).asLongBuffer()

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): Long = elements.get(index)

  operator fun set(index: Int, value: Long) {
    elements.put(index, value)
  }

  /** Sets every element to [value]. */
  fun fill(value: Long) {
    for (index in indices) elements.put(index, value)
  }

  /**
   * A buffer over the elements, without a copy, positioned at the first one. Each call returns a
   * new buffer, so their positions are independent.
   */
  fun asLongBuffer(): LongBuffer = elements.duplicate()

  /** A copy of the elements. */
  fun toLongArray(): LongArray = LongArray(size).also { asLongBuffer().get(it) }

  /** Copies all of [source] into this array, starting at element [offset]. */
  fun copyFrom(source: LongArray, offset: Int = 0) {
    asLongBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): LongIterator = object : LongIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun nextLong(): Long = get(index++)
  }
}

/** A JavaScript `BigUint64Array`, of [ULong] elements. */
class BigUint64Array internal constructor(
  jsObject: JavaScriptObject,
  bytes: ByteBuffer,
) : TypedArray(jsObject, bytes), Iterable<ULong> {
  private val elements: LongBuffer = elementsOf(bytes).asLongBuffer()

  /** The number of elements. */
  val size: Int
    get() = elements.capacity()

  /** The valid indices, `0 until size`. */
  val indices: IntRange
    get() = 0 until size

  operator fun get(index: Int): ULong = elements.get(index).toULong()

  operator fun set(index: Int, value: ULong) {
    elements.put(index, value.toLong())
  }

  /** Sets every element to [value]. */
  fun fill(value: ULong) {
    for (index in indices) elements.put(index, value.toLong())
  }

  /**
   * A buffer over the elements' bits, without a copy, positioned at the first one. Each call
   * returns a new buffer, so their positions are independent.
   */
  fun asLongBuffer(): LongBuffer = elements.duplicate()

  /** A copy of the elements' bits. */
  fun toLongArray(): LongArray = LongArray(size).also { asLongBuffer().get(it) }

  /** Copies all of [source]'s bits into this array, starting at element [offset]. */
  fun copyFrom(source: LongArray, offset: Int = 0) {
    asLongBuffer().apply { position(offset) }.put(source)
  }

  override fun iterator(): Iterator<ULong> = object : Iterator<ULong> {
    private var index = 0

    override fun hasNext(): Boolean = index < size

    override fun next(): ULong = get(index++)
  }
}
