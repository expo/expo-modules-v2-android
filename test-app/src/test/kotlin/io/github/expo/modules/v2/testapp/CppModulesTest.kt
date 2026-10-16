package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.testsupport.TestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Kotlin module that has a C++ module of the same name: the Kotlin one is served. */
@ExpoModule(name = "ShadowedByKotlin")
private class KotlinShadow : Module() {
  @JS
  fun who(): String = "kotlin"
}

@ExpoModule
private class KotlinSide : Module() {
  @JS
  fun twice(value: Int): Int = value * 2
}

/**
 * Modules written with the C++ API (`api/src/main/cpp/expo-modules-cpp`), served by the real JNI
 * bridge next to Kotlin ones. They are compiled into `libexpo-test-support` (CppTestModules.cpp),
 * the way a module library outside the bridge is, and added to each runtime's context here: an
 * `EXPO_MODULE` is served by every runtime in the process, and the tests that list `expo.modules`
 * would see them all.
 *
 * The C++ API's own behavior is covered by its Hermes suite (`api/src/test/cpp`). What only this
 * test can show is the seam: that the bridge serves them, and that the JS thread a Kotlin
 * `JSScheduler` owns is where their promises settle and their events arrive.
 */
class CppModulesTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withRuntime(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      TestSupport.installCppModules(runtime)
      block(runtime)
    }
  }

  private fun HermesRuntime.awaitSettled(script: String): String {
    runEventLoop { !evaluate("$script === null").getBool() }
    return evaluateAsString(script)
  }

  @Test
  fun `a module written in C++ answers from JavaScript`() = withRuntime { runtime ->
    assertEquals("5", runtime.evaluateAsString("expo.modules.CppCalc.add(2, 3)"))
    assertEquals("3.14159", runtime.evaluateAsString("expo.modules.CppCalc.PI"))
    assertEquals("Hello, Expo!", runtime.evaluateAsString("expo.modules.CppCalc.greet('Expo')"))
    assertEquals("Hey, Expo!", runtime.evaluateAsString("expo.modules.CppCalc.greet('Expo', 'Hey')"))
  }

  @Test
  fun `a C++ module is the same object every time and is not enumerated twice`() = withRuntime { runtime ->
    assertEquals("true", runtime.evaluateAsString("expo.modules.CppCalc === expo.modules.CppCalc"))
    assertEquals(
      "1",
      runtime.evaluateAsString("Object.keys(expo.modules).filter((n) => n === 'CppCalc').length"),
    )
  }

  @Test
  fun `properties, methods, records and enums cross`() = withRuntime { runtime ->
    assertEquals("3", runtime.evaluateAsString("expo.modules.CppCalc.factor"))
    runtime.evaluate("expo.modules.CppCalc.factor = 4")
    assertEquals("8", runtime.evaluateAsString("expo.modules.CppCalc.scaled(2)"))
    assertEquals("slow", runtime.evaluateAsString("expo.modules.CppCalc.mode('fast')"))
    assertEquals(
      """{"sensor":"t","value":42,"unit":null}""",
      runtime.evaluateAsString("JSON.stringify(expo.modules.CppCalc.reading({ sensor: 't', value: 21 }))"),
    )
  }

  @Test
  fun `a bad argument and a thrown exception reach JavaScript as errors`() = withRuntime { runtime ->
    assertEquals(
      "Invalid argument 2 of 'add': expected a number, got a string",
      runtime.evaluateAsString("(() => { try { expo.modules.CppCalc.add(1, 'x'); } catch (e) { return e.message; } })()"),
    )
    assertEquals(
      "ERR_CPP|failed in C++",
      runtime.evaluateAsString("(() => { try { expo.modules.CppCalc.fail(); } catch (e) { return e.code + '|' + e.message; } })()"),
    )
  }

  @Test
  fun `a shared object written in C++ is constructed, called, and read`() = withRuntime { runtime ->
    assertEquals(
      "6,6,counter",
      runtime.evaluateAsString("const c = new expo.modules.CppCalc.CppCounter(5); [c.increment(), c.value, c.kind].join()"),
    )
    assertEquals("11", runtime.evaluateAsString("expo.modules.CppCalc.make().increment()"))
    assertEquals(
      "true",
      runtime.evaluateAsString("new expo.modules.CppCalc.CppCounter(1) instanceof expo.modules.CppCalc.CppCounter"),
    )
  }

  @Test
  fun `a C++ event is delivered inside the call that emitted it`() = withRuntime { runtime ->
    assertEquals(
      "1,2",
      runtime.evaluateAsString(
        "globalThis.seen = []; expo.modules.CppCalc.addListener('tick', (n) => seen.push(n));" +
          "expo.modules.CppCalc.tick(1); expo.modules.CppCalc.tick(2); seen.join()",
      ),
    )
  }

  @Test
  fun `a C++ event emitted from a native thread arrives on the JS thread when it drains`() = withRuntime { runtime ->
    runtime.evaluate("globalThis.seen = []; expo.modules.CppCalc.addListener('tick', (n) => seen.push(n));")
    runtime.evaluate("expo.modules.CppCalc.tickFromThread(7);")

    // Posted through the Kotlin scheduler, not run on the thread that emitted it.
    runtime.runEventLoop { runtime.evaluate("seen.length > 0").getBool() }
    assertEquals("7", runtime.evaluateAsString("seen.join()"))
  }

  @Test
  fun `a shared object's C++ event reaches its listener`() = withRuntime { runtime ->
    assertEquals(
      "11",
      runtime.evaluateAsString(
        "globalThis.seen = []; const c = new expo.modules.CppCalc.CppCounter(10);" +
          "c.addListener('changed', (n) => seen.push(n)); c.increment(); seen.join()",
      ),
    )
  }

  @Test
  fun `a promise settled from another thread resolves on the JS thread`() = withRuntime { runtime ->
    runtime.evaluate("globalThis.out = null; expo.modules.CppCalc.later(21).then((v) => { globalThis.out = v; });")
    assertEquals("42", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a promise rejected from another thread carries its code`() = withRuntime { runtime ->
    runtime.evaluate(
      "globalThis.out = null; expo.modules.CppCalc.rejectLater().catch((e) => { globalThis.out = e.code + '|' + e.message; });",
    )
    assertEquals("ERR_LATER|rejected later", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a coroutine continues on another thread and settles on the JS thread`() = withRuntime { runtime ->
    runtime.evaluate("globalThis.out = null; expo.modules.CppCalc.hop(41).then((v) => { globalThis.out = v; });")
    assertEquals("42", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a coroutine can come back to the JS thread to call JavaScript`() = withRuntime { runtime ->
    runtime.evaluate(
      "globalThis.out = null; expo.modules.CppCalc.hopBack((n) => n * 2).then((v) => { globalThis.out = v; });",
    )
    assertEquals("41", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a function run in the background returns a promise`() = withRuntime { runtime ->
    runtime.evaluate("globalThis.out = null; expo.modules.CppCalc.inBackground(6, 7).then((v) => { globalThis.out = v; });")
    assertEquals("42", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a Kotlin module and a C++ module work side by side`() = withRuntime { runtime ->
    runtime.moduleRegistry.register(KotlinSide())
    assertEquals("14", runtime.evaluateAsString("expo.modules.KotlinSide.twice(expo.modules.CppCalc.add(3, 4))"))
    assertEquals(
      "true",
      runtime.evaluateAsString("['KotlinSide', 'CppCalc'].every((n) => Object.keys(expo.modules).includes(n))"),
    )
  }

  @Test
  fun `a Kotlin module wins a name a C++ module also has`() = withRuntime { runtime ->
    runtime.moduleRegistry.register(KotlinShadow())
    assertEquals("kotlin", runtime.evaluateAsString("expo.modules.ShadowedByKotlin.who()"))
    assertEquals(
      "1",
      runtime.evaluateAsString("Object.keys(expo.modules).filter((n) => n === 'ShadowedByKotlin').length"),
    )
  }

  @Test
  fun `the C++ module is there when no Kotlin module has the name`() = withRuntime { runtime ->
    assertEquals("c++", runtime.evaluateAsString("expo.modules.ShadowedByKotlin.who()"))
  }

  @Test
  fun `a module registered statically is served by every runtime until it is taken off`() {
    TestSupport.registerStaticCppModule()
    try {
      HermesRuntime().use { runtime ->
        assertEquals("static", runtime.evaluateAsString("expo.modules.CppStaticProbe.probe()"))
        assertTrue(
          runtime.evaluateAsString("Object.keys(expo.modules).includes('CppStaticProbe')").toBoolean(),
        )
      }
    } finally {
      TestSupport.unregisterStaticCppModule()
    }

    HermesRuntime().use { runtime ->
      assertEquals("undefined", runtime.evaluateAsString("typeof expo.modules.CppStaticProbe"))
    }
  }

  @Test
  fun `a name nobody has is still undefined`() = withRuntime { runtime ->
    assertEquals("undefined", runtime.evaluateAsString("typeof expo.modules.NobodyHasThis"))
  }

  @Test
  fun `closing the runtime destroys its C++ modules`() {
    var before = -1
    withRuntime { runtime ->
      before = runtime.evaluateAsString("expo.modules.CppLifecycle.destroyedCount()").toInt()
      assertEquals("lifecycle", runtime.evaluateAsString("expo.modules.CppLifecycle.name()"))
    }

    withRuntime { runtime ->
      val after = runtime.evaluateAsString("expo.modules.CppLifecycle.destroyedCount()").toInt()
      assertTrue(after > before, "onDestroy ran $before times before, and $after after the first runtime closed")
    }
  }

  @Test
  fun `a runtime closes cleanly with a native job still posted`() {
    withRuntime { runtime ->
      runtime.evaluate("expo.modules.CppCalc.addListener('tick', () => {}); expo.modules.CppCalc.tickFromThread(1);")
      // Closed without draining: the posted job must find the runtime gone, not crash.
    }
    withRuntime { runtime ->
      assertEquals("5", runtime.evaluateAsString("expo.modules.CppCalc.add(2, 3)"))
    }
  }

  @Test
  fun `two runtimes each have their own C++ module instance`() {
    withRuntime { first ->
      withRuntime { second ->
        first.evaluate("expo.modules.CppCalc.factor = 10")
        assertEquals("3", second.evaluateAsString("expo.modules.CppCalc.factor"))
        assertEquals("10", first.evaluateAsString("expo.modules.CppCalc.factor"))
      }
    }
  }
}
