package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoContext
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.jsi.JavaScriptRuntime
import io.github.expo.modules.v2.modules.ModuleRegistry
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Records the context it saw while its constructor ran, before anything could bind it later. */
@ExpoSharedObject
private class Probe(private val label: String) : SharedObject() {
  val contextInConstructor: ExpoContext? = contextOrNull

  @JS
  fun label(): String = label
}

@ExpoSharedObject
private class Handle(context: ExpoContext, value: StringBuilder) :
  SharedRef<StringBuilder>(context, value)

@ExpoModule(classes = [Probe::class])
private class Probes : Module() {
  var last: Probe? = null
    private set

  /** Made off the JS thread, the way a body on `Dispatchers.IO` would make it. */
  var offThread: Probe? = null

  /** The runtime that made the last `remember` call, as a module serving several runtimes sees it. */
  var caller: JavaScriptRuntime? = null
    private set

  @JS
  fun create(label: String): Probe = Probe(label).also { last = it }

  @JS
  fun offThread(): Probe = requireNotNull(offThread)

  @JS
  fun last(): Probe = requireNotNull(last)

  @JS
  fun remember(probe: Probe): String {
    last = probe
    caller = JavaScriptRuntime.current
    return probe.label()
  }
}

class RuntimeBindingTest {
  init {
    ExpoHermes.ensureLoaded()
  }

  /** Runs [body] on a thread of its own, the way a second runtime's JS thread would. */
  private fun <T> onOtherThread(body: () -> T): T {
    var result: Result<T>? = null
    Thread { result = runCatching(body) }.apply {
      start()
      join()
    }
    return requireNotNull(result).getOrThrow()
  }

  @Test
  fun `a module is bound to the context of the runtime it is registered with`() {
    HermesRuntime().use { runtime ->
      val module = Probes()
      runtime.moduleRegistry.register(module)
      assertSame(runtime.context, module.context)
    }
  }

  @Test
  fun `a module registered before the runtime exists is bound when it is created`() {
    val registry = ModuleRegistry()
    val module = Probes()
    registry.register(module)
    assertNull(module.contextOrNull)

    HermesRuntime(registry).use { runtime ->
      assertSame(runtime.context, module.context)
    }
  }

  @Test
  fun `a module that outlives its context is re-bound to the next one`() {
    val module = Probes()
    HermesRuntime().use { first ->
      first.moduleRegistry.register(module)
      assertSame(first.context, module.context)
    }
    HermesRuntime().use { second ->
      second.moduleRegistry.register(module)
      assertSame(second.context, module.context)
    }
  }

  @Test
  fun `a shared object built by JavaScript new has its context in its constructor`() {
    HermesRuntime().use { runtime ->
      val module = Probes()
      runtime.moduleRegistry.register(module)

      assertEquals(
        "a",
        runtime.evaluateAsString(
          "const p = new expo.modules.Probes.Probe('a'); expo.modules.Probes.remember(p)",
        ),
      )
      assertSame(runtime.context, requireNotNull(module.last).contextInConstructor)
    }
  }

  @Test
  fun `a shared object built by a synchronous export has its context in its constructor`() {
    HermesRuntime().use { runtime ->
      val module = Probes()
      runtime.moduleRegistry.register(module)

      assertEquals("b", runtime.evaluateAsString("expo.modules.Probes.create('b').label()"))
      assertSame(runtime.context, requireNotNull(module.last).contextInConstructor)
    }
  }

  @Test
  fun `a shared object built off the JS thread is bound once it is handed to JavaScript`() {
    HermesRuntime().use { runtime ->
      val module = Probes()
      runtime.moduleRegistry.register(module)

      val probe = onOtherThread { Probe("c") }
      module.offThread = probe
      assertNull(probe.contextInConstructor)
      assertFailsWith<IllegalStateException> { probe.context }

      assertEquals("c", runtime.evaluateAsString("expo.modules.Probes.offThread().label()"))
      assertSame(runtime.context, probe.context)
    }
  }

  @Test
  fun `a shared object built with an explicit context is bound on any thread`() {
    ExpoContext().use { context ->
      val handle = onOtherThread { Handle(context, StringBuilder("x")) }
      assertSame(context, handle.context)
    }
  }

  @Test
  fun `runtimes that share a context share one module and its shared objects`() {
    ExpoContext().use { context ->
      val module = Probes()
      HermesRuntime(context = context).use { first ->
        first.moduleRegistry.register(module)
        first.evaluate("expo.modules.Probes.create('shared')")
        val probe = requireNotNull(module.last)

        // A second runtime on its own JS thread, as one per thread requires.
        onOtherThread {
          HermesRuntime(context = context).use { second ->
            second.moduleRegistry.register(module)
            // The same Kotlin instance crosses into the second runtime and comes back.
            assertEquals(
              "shared",
              second.evaluateAsString("expo.modules.Probes.remember(expo.modules.Probes.last())"),
            )
            assertSame(second, module.caller)
          }
        }

        assertSame(probe, module.last)
        assertSame(context, module.context)
        assertSame(context, probe.context)
      }
    }
  }

  @Test
  fun `an object of a live context cannot join a different one`() {
    val module = Probes()
    HermesRuntime().use { first ->
      first.moduleRegistry.register(module)
      onOtherThread {
        HermesRuntime().use { second ->
          val error = assertFailsWith<IllegalStateException> {
            second.moduleRegistry.register(module)
          }
          assertTrue("another ExpoContext" in error.message.orEmpty(), "unexpected: ${error.message}")
        }
      }
      assertSame(first.context, module.context)
    }
  }

  @Test
  fun `a runtime closes the context it created, but not one it was given`() {
    val given = ExpoContext()
    HermesRuntime(context = given).close()
    assertFalse(given.isClosed)

    val runtime = HermesRuntime()
    runtime.close()
    assertTrue(runtime.context.isClosed)
  }

  @Test
  fun `the current runtime is the one of the calling JS thread`() {
    HermesRuntime().use { runtime ->
      assertSame(runtime, JavaScriptRuntime.current)
      assertNull(onOtherThread { JavaScriptRuntime.current })
    }
    assertNull(JavaScriptRuntime.current)
  }

  @Test
  fun `an object of a closed context reports that context as gone`() {
    val module = Probes()
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(module)
    }
    assertNull(module.contextOrNull)
    val error = assertFailsWith<IllegalStateException> { module.context }
    assertTrue("closed" in error.message.orEmpty(), "unexpected message: ${error.message}")
  }
}
