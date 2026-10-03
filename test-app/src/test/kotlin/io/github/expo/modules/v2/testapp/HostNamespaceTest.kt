package io.github.expo.modules.v2.testapp

import io.github.expo.hermes.HermesRuntime as HermesEngine
import io.github.expo.modules.v2.ExpoContext
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.jsi.AttachedRuntime
import io.github.expo.modules.v2.testsupport.ExpoHermes
import kotlin.test.Test
import kotlin.test.assertEquals

@ExpoModule(name = "Joined")
private class JoinedModule : Module() {
  @JS
  fun answer(): Int = 42
}

/** Registered under the name the host's own module already has. */
@ExpoModule(name = "Classic")
private class ClassicModule : Module() {
  @JS
  fun source(): String = "v2"
}

/**
 * A host that owns the global namespace before the runtime attaches, the way `expo-modules-core`
 * owns `globalThis.expo` in a React Native app. The runtime has to join that namespace: the host's
 * modules and everything else on it stay reachable, and one `expo.modules` serves both.
 */
class HostNamespaceTest {
  init {
    ExpoHermes.ensureLoaded()
  }

  /** The shape `expo-modules-core` installs: a non-writable, non-configurable global. */
  private val hostNamespace = """
    Object.defineProperty(globalThis, 'expo', {
      value: {
        modules: { Classic: { source() { return 'v1'; } } },
        hostValue: 'kept',
      },
      enumerable: true,
    });
  """

  /** Runs [body] against a runtime attached to an engine that ran [prelude] first. */
  private fun <T> attached(prelude: String, vararg modules: Module, body: (AttachedRuntime) -> T): T =
    HermesEngine().use { engine ->
      engine.evaluate(prelude)
      ExpoContext().use { context ->
        modules.forEach(context.moduleRegistry::register)
        AttachedRuntime(engine.pointer, context = context).use(body)
      }
    }

  /**
   * [script]'s result as a string. `String(...)` runs in JavaScript, so a wrong result fails the
   * assertion instead of reading a value of the wrong type through JNI.
   */
  private fun AttachedRuntime.string(script: String): String = evaluate("String($script)").getString()

  @Test
  fun `modules join the namespace the host already installed`() {
    attached(hostNamespace, JoinedModule()) { runtime ->
      assertEquals("42", runtime.string("expo.modules.Joined.answer()"))
      assertEquals("v1", runtime.string("expo.modules.Classic.source()"))
      assertEquals("kept", runtime.string("expo.hostValue"))
    }
  }

  @Test
  fun `a module the host has wins over a registered one of the same name`() {
    attached(hostNamespace, ClassicModule()) { runtime ->
      assertEquals("v1", runtime.string("expo.modules.Classic.source()"))
    }
  }

  @Test
  fun `the namespace lists the host's modules and the registered ones once each`() {
    attached(hostNamespace, JoinedModule(), ClassicModule()) { runtime ->
      assertEquals(
        "Classic,Joined",
        runtime.string("Object.keys(expo.modules).sort()"),
      )
    }
  }

  @Test
  fun `a name neither side has is undefined`() {
    attached(hostNamespace, JoinedModule()) { runtime ->
      assertEquals("undefined", runtime.string("typeof expo.modules.Missing"))
    }
  }

  @Test
  fun `a namespace without modules gets the registered ones`() {
    attached("globalThis.expo = { hostValue: 'kept' };", JoinedModule()) { runtime ->
      assertEquals("42", runtime.string("expo.modules.Joined.answer()"))
      assertEquals("kept", runtime.string("expo.hostValue"))
    }
  }
}
