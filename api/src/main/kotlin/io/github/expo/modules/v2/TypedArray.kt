package io.github.expo.modules.v2

import io.github.expo.modules.v2.jsi.JavaScriptObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A typed array, such as a `Uint8Array` or a `Float32Array`, or a `DataView`, that JavaScript
 * passed in. Its bytes are the ones JavaScript sees, so [write] changes the JavaScript array in
 * place, without a copy.
 *
 * The bytes belong to JavaScript. Use a typed array only in a synchronous export, on the JS thread,
 * and do not keep it, or a buffer from [toDirectBuffer], after the call returns.
 */
class TypedArray internal constructor(
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
