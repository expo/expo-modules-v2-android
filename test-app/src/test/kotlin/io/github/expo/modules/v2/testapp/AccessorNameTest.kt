package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

@ExpoModule(name = "Accessors")
private class Accessors : Module() {
  @JS
  var ärger: Int = 1

  @JS
  var isÖffentlich: Boolean = true

  @JS
  var isöffentlich: Boolean = false
}

class AccessorNameTest {
  @Test
  fun `non-ASCII property names reach the accessors Kotlin generated`() {
    ExpoHermes.ensureLoaded()

    HermesRuntime().use { runtime ->
      val module = Accessors()
      runtime.moduleRegistry.register(module)

      runtime.evaluate(
        """
        const a = expo.modules.Accessors;
        a.ärger = a.ärger + 41;
        a.isÖffentlich = !a.isÖffentlich;
        a.isöffentlich = !a.isöffentlich;
        """.trimIndent(),
      )

      assertEquals(42, module.ärger)
      assertEquals(false, module.isÖffentlich)
      assertEquals(true, module.isöffentlich)
      assertEquals(42, runtime.evaluate("expo.modules.Accessors.ärger").getInt())
    }
  }
}
