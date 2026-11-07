package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.BigInt64Array
import io.github.expo.modules.v2.BigUint64Array
import io.github.expo.modules.v2.DataView
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.Float32Array
import io.github.expo.modules.v2.Float64Array
import io.github.expo.modules.v2.Int16Array
import io.github.expo.modules.v2.Int32Array
import io.github.expo.modules.v2.Int8Array
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.TypedArray
import io.github.expo.modules.v2.Uint16Array
import io.github.expo.modules.v2.Uint32Array
import io.github.expo.modules.v2.Uint8Array
import io.github.expo.modules.v2.Uint8ClampedArray
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExpoModule
private class Bytes : Module() {
  @JS
  fun fill(array: TypedArray, value: Int) {
    array.write(ByteArray(array.byteLength) { value.toByte() }, 0, array.byteLength)
  }

  @JS
  fun sum(array: TypedArray): Int {
    val buffer = array.toDirectBuffer()
    var sum = 0
    while (buffer.hasRemaining()) {
      sum += buffer.get().toInt() and 0xff
    }
    return sum
  }

  @JS
  fun firstTwo(array: TypedArray): String {
    val bytes = ByteArray(2)
    array.read(bytes, 0, 2)
    return bytes.joinToString(",")
  }

  @JS
  fun byteLength(array: TypedArray): Int = array.byteLength

  @JS
  fun copy(output: TypedArray, input: TypedArray) {
    output.toDirectBuffer().put(input.toDirectBuffer())
  }

  @JS
  fun same(array: TypedArray): TypedArray = array

  @JS
  fun lengthOrMinusOne(array: TypedArray?): Int = array?.byteLength ?: -1
}

/** Typed arrays of one kind, read and written element by element. */
@ExpoModule
private class Samples : Module() {
  @JS
  fun scale(samples: Float32Array, factor: Float) {
    for (i in samples.indices) {
      samples[i] *= factor
    }
  }

  @JS
  fun sum(values: Int32Array): Int = values.sum()

  @JS
  fun size(values: Float64Array): Int = values.size

  @JS
  fun join(values: Int16Array): String = values.joinToString(",")

  @JS
  fun negate(values: Int8Array) {
    for (i in values.indices) {
      values[i] = (-values[i]).toByte()
    }
  }

  @JS
  fun max(values: Uint8Array): Int = values.max().toInt()

  @JS
  fun invert(pixels: Uint8ClampedArray) {
    for (i in pixels.indices) {
      pixels[i] = (255u - pixels[i]).toUByte()
    }
  }

  @JS
  fun joinUint16(values: Uint16Array): String = values.joinToString(",")

  @JS
  fun joinUint32(values: Uint32Array): String = values.joinToString(",")

  @JS
  fun joinBigInt64(values: BigInt64Array): String = values.joinToString(",")

  @JS
  fun joinBigUint64(values: BigUint64Array): String = values.joinToString(",")

  @JS
  fun fillBigUint64(values: BigUint64Array) {
    values.fill(ULong.MAX_VALUE)
  }

  @JS
  fun copyIn(target: Float32Array) {
    target.copyFrom(floatArrayOf(1.5f, 2.5f), offset = 1)
  }

  @JS
  fun copyOut(values: Float64Array): String = values.toDoubleArray().joinToString(",")

  @JS
  fun firstViaBuffer(values: Int32Array): Int = values.asIntBuffer().get()

  @JS
  fun at(values: Float32Array, index: Int): Float = values[index]

  @JS
  fun kindOf(array: TypedArray): String =
    when (array) {
      is Int8Array -> "Int8Array"
      is Uint8Array -> "Uint8Array"
      is Uint8ClampedArray -> "Uint8ClampedArray"
      is Int16Array -> "Int16Array"
      is Uint16Array -> "Uint16Array"
      is Int32Array -> "Int32Array"
      is Uint32Array -> "Uint32Array"
      is Float32Array -> "Float32Array"
      is Float64Array -> "Float64Array"
      is BigInt64Array -> "BigInt64Array"
      is BigUint64Array -> "BigUint64Array"
      is DataView -> "DataView"
    }

  @JS
  fun same(samples: Float32Array): Float32Array = samples

  @JS
  fun sizeOrMinusOne(samples: Float32Array?): Int = samples?.size ?: -1
}

class TypedArrayTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withBytes(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Bytes())
      block(runtime)
    }
  }

  private fun withSamples(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Samples())
      block(runtime)
    }
  }

  private fun HermesRuntime.errorMessageOf(expression: String): String =
    evaluateAsString(
      "(() => { try { $expression; return 'no-throw'; } catch (e) { return e.message; } })()",
    )

  @Test
  fun `a write lands in the JavaScript array`() = withBytes { runtime ->
    assertEquals(
      "7,7,7,7",
      runtime.evaluateAsString("const a = new Uint8Array(4); expo.modules.Bytes.fill(a, 7); a.join()"),
    )
  }

  @Test
  fun `a view writes only its own window of the buffer`() = withBytes { runtime ->
    assertEquals(
      "0,0,1,1,1,0,0,0",
      runtime.evaluateAsString(
        "const b = new Uint8Array(8);" +
          "expo.modules.Bytes.fill(new Uint8Array(b.buffer, 2, 3), 1); b.join()",
      ),
    )
  }

  @Test
  fun `reads see the JavaScript bytes`() = withBytes { runtime ->
    assertEquals("6", runtime.evaluateAsString("expo.modules.Bytes.sum(new Uint8Array([1, 2, 3]))"))
    assertEquals(
      "5,6",
      runtime.evaluateAsString("expo.modules.Bytes.firstTwo(new Uint8Array([5, 6, 7]))"),
    )
  }

  @Test
  fun `every kind of view crosses, by its byte length`() = withBytes { runtime ->
    assertEquals("16", runtime.evaluateAsString("expo.modules.Bytes.byteLength(new Float64Array(2))"))
    assertEquals(
      "5",
      runtime.evaluateAsString("expo.modules.Bytes.byteLength(new DataView(new ArrayBuffer(5)))"),
    )
  }

  @Test
  fun `two typed arrays can cross in one call`() = withBytes { runtime ->
    assertEquals(
      "1,2,3",
      runtime.evaluateAsString(
        "const out = new Uint8Array(3);" +
          "expo.modules.Bytes.copy(out, new Uint8Array([1, 2, 3])); out.join()",
      ),
    )
  }

  @Test
  fun `a returned typed array is the same JavaScript object`() = withBytes { runtime ->
    assertEquals(
      "true",
      runtime.evaluateAsString("const a = new Int16Array(1); expo.modules.Bytes.same(a) === a"),
    )
  }

  @Test
  fun `a nullable typed array takes null`() = withBytes { runtime ->
    assertEquals("-1", runtime.evaluateAsString("expo.modules.Bytes.lengthOrMinusOne(null)"))
    assertEquals("3", runtime.evaluateAsString("expo.modules.Bytes.lengthOrMinusOne(new Uint8Array(3))"))
  }

  @Test
  fun `a value that is not a typed array or a DataView is rejected`() = withBytes { runtime ->
    for (value in listOf("[1, 2]", "new ArrayBuffer(2)", "{ buffer: new ArrayBuffer(2) }")) {
      val message = runtime.evaluateAsString(
        "(() => { try { expo.modules.Bytes.byteLength($value); return 'no-throw'; } " +
          "catch (e) { return e.message; } })()",
      )
      assertTrue("typed array" in message, "unexpected message for $value: $message")
    }
  }

  @Test
  fun `an element write lands in the JavaScript array`() = withSamples { runtime ->
    assertEquals(
      "2,-3,0.5",
      runtime.evaluateAsString(
        "const a = new Float32Array([1, -1.5, 0.25]); expo.modules.Samples.scale(a, 2); a.join()",
      ),
    )
  }

  @Test
  fun `elements read in native order, including negative ones`() = withSamples { runtime ->
    assertEquals("-6", runtime.evaluateAsString("expo.modules.Samples.sum(new Int32Array([1, -2, -5]))"))
    assertEquals("3", runtime.evaluateAsString("expo.modules.Samples.size(new Float64Array(3))"))
    assertEquals(
      "-32768,0,32767",
      runtime.evaluateAsString("expo.modules.Samples.join(new Int16Array([-32768, 0, 32767]))"),
    )
    assertEquals(
      "-1,2,-127",
      runtime.evaluateAsString(
        "const a = new Int8Array([1, -2, 127]); expo.modules.Samples.negate(a); a.join()",
      ),
    )
  }

  @Test
  fun `an unsigned kind reads its full range`() = withSamples { runtime ->
    assertEquals("255", runtime.evaluateAsString("expo.modules.Samples.max(new Uint8Array([3, 255, 128]))"))
    assertEquals(
      "0,65535",
      runtime.evaluateAsString("expo.modules.Samples.joinUint16(new Uint16Array([0, 65535]))"),
    )
    assertEquals(
      "4294967295",
      runtime.evaluateAsString("expo.modules.Samples.joinUint32(new Uint32Array([4294967295]))"),
    )
    assertEquals(
      "18446744073709551615",
      runtime.evaluateAsString("expo.modules.Samples.joinBigUint64(new BigUint64Array([2n ** 64n - 1n]))"),
    )
    assertEquals(
      "18446744073709551615",
      runtime.evaluateAsString(
        "const a = new BigUint64Array(1); expo.modules.Samples.fillBigUint64(a); a[0].toString()",
      ),
    )
  }

  @Test
  fun `a Uint8ClampedArray reads and writes its bytes`() = withSamples { runtime ->
    assertEquals(
      "255,0,155",
      runtime.evaluateAsString(
        "const a = new Uint8ClampedArray([0, 255, 100]); expo.modules.Samples.invert(a); a.join()",
      ),
    )
  }

  @Test
  fun `a BigInt64Array reads signed 64-bit elements`() = withSamples { runtime ->
    assertEquals(
      "-9223372036854775808,2",
      runtime.evaluateAsString("expo.modules.Samples.joinBigInt64(new BigInt64Array([-(2n ** 63n), 2n]))"),
    )
  }

  @Test
  fun `bulk copies go both ways`() = withSamples { runtime ->
    assertEquals(
      "0,1.5,2.5",
      runtime.evaluateAsString("const a = new Float32Array(3); expo.modules.Samples.copyIn(a); a.join()"),
    )
    assertEquals(
      "0.5,-1.0",
      runtime.evaluateAsString("expo.modules.Samples.copyOut(new Float64Array([0.5, -1]))"),
    )
    assertEquals("42", runtime.evaluateAsString("expo.modules.Samples.firstViaBuffer(new Int32Array([42, 7]))"))
  }

  @Test
  fun `a view at an offset sees only its own elements`() = withSamples { runtime ->
    assertEquals(
      "1,4,6,4",
      runtime.evaluateAsString(
        "const all = new Float32Array([1, 2, 3, 4]);" +
          "expo.modules.Samples.scale(new Float32Array(all.buffer, 4, 2), 2); all.join()",
      ),
    )
  }

  @Test
  fun `a TypedArray arrives as its own kind`() = withSamples { runtime ->
    val kinds = listOf(
      "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array",
      "Uint32Array", "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array",
    )
    for (kind in kinds) {
      assertEquals(kind, runtime.evaluateAsString("expo.modules.Samples.kindOf(new $kind(2))"))
    }
    assertEquals(
      "DataView",
      runtime.evaluateAsString("expo.modules.Samples.kindOf(new DataView(new ArrayBuffer(2)))"),
    )
  }

  @Test
  fun `a subclass of a typed array arrives as the kind it extends`() = withSamples { runtime ->
    assertEquals(
      "Float32Array",
      runtime.evaluateAsString(
        "class Samples extends Float32Array {}; expo.modules.Samples.kindOf(new Samples(2))",
      ),
    )
    assertEquals(
      "2,4",
      runtime.evaluateAsString(
        "class Audio extends Float32Array {};" +
          "const a = new Audio([1, 2]); expo.modules.Samples.scale(a, 2); a.join()",
      ),
    )
  }

  @Test
  fun `another kind is rejected by name`() = withSamples { runtime ->
    val message = runtime.errorMessageOf("expo.modules.Samples.scale(new Uint8Array(2), 2)")
    assertTrue("Expected a Float32Array, but got a Uint8Array" in message, message)
    val dataView = runtime.errorMessageOf("expo.modules.Samples.sum(new DataView(new ArrayBuffer(4)))")
    assertTrue("Expected an Int32Array, but got a DataView" in dataView, dataView)
  }

  @Test
  fun `a bare ArrayBuffer is rejected with a hint`() = withSamples { runtime ->
    val message = runtime.errorMessageOf("expo.modules.Samples.scale(new ArrayBuffer(8), 2)")
    assertTrue("new Float32Array(buffer)" in message, message)
  }

  @Test
  fun `an index out of range throws`() = withSamples { runtime ->
    assertEquals("2", runtime.evaluateAsString("expo.modules.Samples.at(new Float32Array([1, 2]), 1)"))
    val message = runtime.errorMessageOf("expo.modules.Samples.at(new Float32Array([1, 2]), 2)")
    assertTrue(message != "no-throw", message)
  }

  @Test
  fun `a kind returns as the same JavaScript object, and takes null when nullable`() = withSamples { runtime ->
    assertEquals(
      "true",
      runtime.evaluateAsString("const a = new Float32Array(1); expo.modules.Samples.same(a) === a"),
    )
    assertEquals("-1", runtime.evaluateAsString("expo.modules.Samples.sizeOrMinusOne(null)"))
    assertEquals("4", runtime.evaluateAsString("expo.modules.Samples.sizeOrMinusOne(new Float32Array(4))"))
  }
}
