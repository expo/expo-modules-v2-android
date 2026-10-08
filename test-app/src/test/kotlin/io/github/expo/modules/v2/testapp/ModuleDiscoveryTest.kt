package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.discoveredExpoModules
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@ExpoModule(name = "Discovered")
private object DiscoveredModule : Module() {
  @JS
  fun answer(): Int = 42
}

/** A `class` rather than an `object`, so a host creates it with its no-argument constructor. */
@ExpoModule(name = "DiscoveredClass")
private class DiscoveredClassModule : Module() {
  @JS
  fun answer(): Int = 7
}

/**
 * `discoveredExpoModules()` through the real Gradle compilation, not the compiler test framework:
 * the plugin is on this source set's compiler classpath the way a consuming app has it.
 */
class ModuleDiscoveryTest {
  @Test
  fun `lists every ExpoModule of this compilation`() {
    val modules = discoveredExpoModules()

    assertContains(modules, DiscoveredModule::class.java)
    // Modules the other test files declare are part of the same compilation, so they show up too.
    assertContains(modules.map { it.name }, "io.github.expo.modules.v2.testapp.DefaultsModule")
    assertEquals(modules.size, modules.distinct().size, "no module is listed twice")
  }

  @Test
  fun `a discovered module registers and answers from JavaScript`() {
    ExpoHermes.ensureLoaded()

    val discovered = discoveredExpoModules().single { it == DiscoveredModule::class.java }
    val instance = discovered.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) as Module

    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(instance)
      assertEquals(42, runtime.evaluate("globalThis.expo.modules.Discovered.answer()").getInt())
    }
  }

  @Test
  fun `a discovered class module is created through its constructor`() {
    ExpoHermes.ensureLoaded()

    // The way a host creates it.
    val discovered = discoveredExpoModules().single { it == DiscoveredClassModule::class.java }
    val instance = discovered.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(instance)
      assertEquals(7, runtime.evaluate("globalThis.expo.modules.DiscoveredClass.answer()").getInt())
    }
  }
}
