package io.github.expo.modules.v2.modules

import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.buffered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the seam the compiler plugin generates into: [Module.define$ExpoModulesV2] fills a
 * [ModuleBuilder] and names the module.
 *
 * The override here is written by hand in exactly the shape the plugin emits, so this test says what
 * the generated code has to produce without needing the plugin — or a runtime — to be involved.
 * `ModuleRegistry.register(module)` itself is exercised in `:test-app`, because constructing a
 * registry loads the native library.
 */
class ModuleDefinitionHookTest {
  private class Greeter : Module() {
    var count: Int = 0

    fun greet(name: String): String = "Hello, $name!"

    override fun `define$ExpoModulesV2`(builder: ModuleBuilder): String? {
      builder.function(
        "greet",
        AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
        returns = AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
        methodName = "greet__trampoline\$ExpoModulesV2",
      )
      builder.property("count", AnyType(TypeDescriptor.Int), mutable = true)
      return "Greeter"
    }
  }

  private class NotAnnotated : Module()

  @Test
  fun `the generated hook names the module and records its exports`() {
    val builder = ModuleBuilder()
    val name = Greeter().`define$ExpoModulesV2`(builder)

    assertEquals("Greeter", name)

    val greet = builder.functions.single()
    assertEquals("greet", greet.jsName)
    assertEquals("greet__trampoline\$ExpoModulesV2", greet.methodName)
    // Both string values are buffered, so both code streams carry the transport flag.
    val buffered = CppType.STRING.code or CppType.USES_BUFFER
    assertEquals(buffered, greet.argTypes.single().single())
    assertEquals(buffered, greet.returnType.single())

    val count = builder.properties.single()
    assertEquals("count", count.jsName)
    assertEquals("getCount", count.getterName)
    assertEquals("setCount", count.setterName)
    assertEquals(CppType.INT.code, count.getterType.single())
  }

  @Test
  fun `a module without the annotation declares no definition`() {
    assertNull(NotAnnotated().`define$ExpoModulesV2`(ModuleBuilder()))
  }
}
