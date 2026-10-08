package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exports declared `private`. The bridge calls them through JNI, which ignores visibility, so they
 * cross like public ones.
 */
@ExpoModule
private class PrivateExports : Module() {
  @JS
  private fun double(value: Int): Int = value * 2

  @JS
  private val answer: Int
    get() = 42
}

class PrivateExportTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withModule(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(PrivateExports())
      block(runtime)
    }
  }

  @Test
  fun `a private function crosses`() = withModule { runtime ->
    assertEquals(42, runtime.evaluate("expo.modules.PrivateExports.double(21)").getInt())
  }

  @Test
  fun `a private property crosses through its getter`() = withModule { runtime ->
    assertEquals(42, runtime.evaluate("expo.modules.PrivateExports.answer").getInt())
  }
}
