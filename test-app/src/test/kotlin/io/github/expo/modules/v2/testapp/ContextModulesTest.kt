package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoContext
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.module
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@ExpoSharedObject
private class ContextProbe : SharedObject() {
  /** What a shared object reaches through its context: the module that owns its state. */
  @JS
  fun ownerHits(): Int = requireNotNull(context.module<ContextOwner>()).hit()
}

@ExpoModule(classes = [ContextProbe::class])
private class ContextOwner : Module() {
  var hits = 0
  var destroyed = 0
  var contextWasOpenOnDestroy = false

  fun hit(): Int = ++hits

  override fun onDestroy() {
    destroyed++
    contextWasOpenOnDestroy = contextOrNull != null
  }
}

@ExpoModule
private class UnrelatedModule : Module()

@ExpoModule
private class FailingToLetGo : Module() {
  override fun onDestroy() = throw IllegalStateException("cannot let go")
}

/** What [block] writes to `System.err`. */
private fun capturingStdErr(block: () -> Unit): String {
  val original = System.err
  val captured = ByteArrayOutputStream()
  System.setErr(PrintStream(captured, true))
  try {
    block()
  } finally {
    System.setErr(original)
  }
  return captured.toString()
}

class ContextModulesTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  @Test
  fun `a context owns its modules, and binds each one when it is registered`() {
    ExpoContext().use { context ->
      val owner = ContextOwner()
      context.moduleRegistry.register(owner)
      assertSame(context, owner.context)
      assertSame(owner, context.module<ContextOwner>())
    }
  }

  @Test
  fun `runtimes that share a context serve its modules, and closing them does not destroy the modules`() {
    val context = ExpoContext()
    val owner = ContextOwner()
    context.moduleRegistry.register(owner)
    val probe = "new expo.modules.ContextOwner.ContextProbe().ownerHits()"

    HermesRuntime(context = context).use { runtime ->
      assertSame(context.moduleRegistry, runtime.moduleRegistry)
      assertEquals("1", runtime.evaluateAsString("String($probe)"))
    }
    HermesRuntime(context = context).use { runtime ->
      assertEquals("2", runtime.evaluateAsString("String($probe)"))
    }
    assertEquals(0, owner.destroyed)

    context.close()
    assertEquals(1, owner.destroyed)
  }

  @Test
  fun `a shared object reaches a module of its own context`() {
    val owner = ContextOwner()
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(owner)
      assertEquals(
        "1,2",
        runtime.evaluateAsString(
          "const probe = new expo.modules.ContextOwner.ContextProbe(); probe.ownerHits() + ',' + probe.ownerHits()",
        ),
      )
      assertSame(owner, runtime.context.module<ContextOwner>())
      assertNull(runtime.context.module<UnrelatedModule>())
    }
  }

  @Test
  fun `a module is destroyed once when its context closes, while the context still reads open`() {
    val owner = ContextOwner()
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(owner)
      assertEquals(0, owner.destroyed)
    }
    assertEquals(1, owner.destroyed)
    assertTrue(owner.contextWasOpenOnDestroy)
  }

  @Test
  fun `closing a context twice destroys its modules once`() {
    val context = ExpoContext()
    val owner = ContextOwner()
    HermesRuntime(context = context).use { runtime -> runtime.moduleRegistry.register(owner) }
    context.close()
    context.close()
    assertEquals(1, owner.destroyed)
    assertNull(context.module<ContextOwner>())
  }

  @Test
  fun `a module that fails to let go is logged, and does not stop the others`() {
    val context = ExpoContext()
    val owner = ContextOwner()
    HermesRuntime(context = context).use { runtime ->
      runtime.moduleRegistry.register(FailingToLetGo())
      runtime.moduleRegistry.register(owner)
    }
    val log = capturingStdErr { context.close() }
    assertTrue(FailingToLetGo::class.java.name in log, "unexpected log: $log")
    assertTrue("cannot let go" in log, "unexpected log: $log")
    assertEquals(1, owner.destroyed)
    assertTrue(context.isClosed)
  }
}
