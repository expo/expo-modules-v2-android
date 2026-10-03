package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.TypedArray
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
}
