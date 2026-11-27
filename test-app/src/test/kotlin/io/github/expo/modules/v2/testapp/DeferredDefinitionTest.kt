package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.modules.ModuleBuilder
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.TypeDescriptor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Overrides both hooks the compiler plugin generates, and counts how often it is defined. */
private class Counted(private val reportedName: String = "Counted") : Module() {
  var defines = 0

  override fun `name$ExpoModulesV2`(): String = reportedName

  override fun `define$ExpoModulesV2`(builder: ModuleBuilder): String {
    defines++
    builder.function("answer", returns = AnyType(TypeDescriptor.Int), methodName = "answer")
    return "Counted"
  }

  @Suppress("unused") // Called through JNI.
  fun answer(): Int = 42
}

/**
 * A module that reports its name is registered by it and defined only when JavaScript first reads
 * it, once - an app registers every module it ships at startup, and touches few while it launches.
 */
class DeferredDefinitionTest {
  @BeforeTest
  fun load() = ExpoHermes.ensureLoaded()

  @Test
  fun `a module is defined when JavaScript first reads it, and only then`() {
    HermesRuntime().use { runtime ->
      val module = Counted()
      assertEquals("Counted", runtime.moduleRegistry.register(module))
      assertEquals(0, module.defines, "registering does not define the module")

      assertEquals(42, runtime.evaluate("expo.modules.Counted.answer()").getInt())
      assertEquals(42, runtime.evaluate("expo.modules.Counted.answer()").getInt())
      assertEquals(1, module.defines)
    }
  }

  @Test
  fun `a module that defines itself under another name fails when JavaScript reads it`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Counted(reportedName = "Renamed"))

      val message = runtime.evaluate(
        "try { expo.modules.Renamed; 'no error' } catch (e) { e.message }",
      ).getString()
      assertTrue("reported the name 'Renamed' but defined itself as 'Counted'" in message, message)
    }
  }
}
