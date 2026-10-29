package io.github.expo.modules.v2

import io.github.expo.modules.v2.jsi.JavaScriptObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A typed array, such as a `Uint8Array` or a `Float32Array`, or a `DataView`, that JavaScript
 * passed in. Its bytes are the ones JavaScript sees, so a write changes the JavaScript array in
 * place, without a copy.
 *
 * Take the kind you expect, such as [Float32Array], to read and write it one element at a time -
 * `array[i]`, `for (x in array)`, [Float32Array.size] - and any other kind is rejected before the
 * call runs. Take `TypedArray` to accept every kind, and tell them apart with `when`.
 *
 * The bytes belong to JavaScript. Use a typed array only in a synchronous export, on the JS thread,
 * and do not keep it, or a buffer from it, after the call returns.
 */
sealed class TypedArray(
  /** The JavaScript object itself. Returning the typed array returns this object. */
  val jsObject: JavaScriptObject,
  private val bytes: ByteBuffer,
) {
  /** The number of bytes the array covers, from its own first byte. */
  val byteLength: Int
    get() = bytes.capacity()

  /**
   * A buffer over the array's bytes, in native byte order and positioned at the first one. Each
   * call returns a new buffer, so their positions are independent.
   */
  fun toDirectBuffer(): ByteBuffer = bytes.duplicate().order(ByteOrder.nativeOrder())

  /** Copies [size] bytes, starting at [position] in this array, to the start of [buffer]. */
  fun read(buffer: ByteArray, position: Int, size: Int) {
    toDirectBuffer().apply { position(position) }.get(buffer, 0, size)
  }

  /** Copies [size] bytes from the start of [buffer] into this array, starting at [position]. */
  fun write(buffer: ByteArray, position: Int, size: Int) {
    toDirectBuffer().apply { position(position) }.put(buffer, 0, size)
  }
}

/**
 * A JavaScript `DataView`. Read and write its bytes with [TypedArray]'s members; for a multi-byte
 * value, set the order of a [toDirectBuffer] - JavaScript's `DataView` reads big-endian unless it is
 * told otherwise.
 */
class DataView internal constructor(jsObject: JavaScriptObject, bytes: ByteBuffer) :
  TypedArray(jsObject, bytes)

/**
 * Each kind of array buffer view, as `JavaScriptObject.getArrayBufferViewKind` numbers it: the
 * native side names a kind by its index here, so the order matches its `kArrayBufferViewTags`.
 */
internal enum class ArrayBufferViewKind(
  val jsName: String,
  val create: (JavaScriptObject, ByteBuffer) -> TypedArray,
) {
  INT8("Int8Array", ::Int8Array),
  UINT8("Uint8Array", ::Uint8Array),
  UINT8_CLAMPED("Uint8ClampedArray", ::Uint8ClampedArray),
  INT16("Int16Array", ::Int16Array),
  UINT16("Uint16Array", ::Uint16Array),
  INT32("Int32Array", ::Int32Array),
  UINT32("Uint32Array", ::Uint32Array),
  FLOAT32("Float32Array", ::Float32Array),
  FLOAT64("Float64Array", ::Float64Array),
  BIG_INT64("BigInt64Array", ::BigInt64Array),
  BIG_UINT64("BigUint64Array", ::BigUint64Array),
  DATA_VIEW("DataView", ::DataView),
}
