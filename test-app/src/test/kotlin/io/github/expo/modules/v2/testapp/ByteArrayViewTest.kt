package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A `ByteArray` argument, on each transport it can take. */
@ExpoModule
private class Blobs : Module() {
  @JS
  fun join(bytes: ByteArray): String = bytes.joinToString(",")

  @JS
  fun joinOrNull(bytes: ByteArray?): String = bytes?.joinToString(",") ?: "null"

  @JS
  @BufferMode(Buffer.YES)
  fun joinBuffered(bytes: ByteArray): String = bytes.joinToString(",")
}

class ByteArrayViewTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withBlobs(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Blobs())
      block(runtime)
    }
  }

  @Test
  fun `a ByteArray takes an ArrayBuffer`() = withBlobs { runtime ->
    assertEquals(
      "1,2",
      runtime.evaluateAsString("expo.modules.Blobs.join(new Uint8Array([1, 2]).buffer)"),
    )
  }

  @Test
  fun `a ByteArray takes a typed array, only its own window of the buffer`() = withBlobs { runtime ->
    assertEquals("1,2,3", runtime.evaluateAsString("expo.modules.Blobs.join(new Uint8Array([1, 2, 3]))"))
    assertEquals(
      "2,3",
      runtime.evaluateAsString(
        "expo.modules.Blobs.join(new Uint8Array(new Uint8Array([1, 2, 3, 4]).buffer, 1, 2))",
      ),
    )
    assertEquals(
      "0,0,0,0",
      runtime.evaluateAsString("expo.modules.Blobs.join(new DataView(new ArrayBuffer(4)))"),
    )
  }

  @Test
  fun `a buffered ByteArray takes a typed array too`() = withBlobs { runtime ->
    assertEquals(
      "5,6",
      runtime.evaluateAsString("expo.modules.Blobs.joinBuffered(new Uint8Array([5, 6]))"),
    )
  }

  @Test
  fun `a nullable ByteArray takes null`() = withBlobs { runtime ->
    assertEquals("null", runtime.evaluateAsString("expo.modules.Blobs.joinOrNull(null)"))
    assertEquals("7", runtime.evaluateAsString("expo.modules.Blobs.joinOrNull(new Uint8Array([7]))"))
  }

  @Test
  fun `a value that holds no bytes is rejected`() = withBlobs { runtime ->
    for (value in listOf("[1, 2]", "{}", "'bytes'")) {
      val message = runtime.evaluateAsString(
        "(() => { try { expo.modules.Blobs.join($value); return 'no-throw'; } " +
          "catch (e) { return e.message; } })()",
      )
      assertTrue("ArrayBuffer" in message, "unexpected message for $value: $message")
    }
  }
}
