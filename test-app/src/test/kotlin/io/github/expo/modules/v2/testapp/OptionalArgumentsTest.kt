package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExpoSharedObject
private class Connection @JS constructor(
  private val path: String,
  private val options: Map<String, Any?>?,
  private val data: ByteArray?,
) : SharedObject() {
  @JS
  fun describe(): String = "$path/${options?.size}/${data?.size}"
}

@ExpoModule(classes = [Connection::class])
private class Optionals : Module() {
  @JS
  fun greet(name: String, greeting: String?, punctuation: String?): String =
    "${greeting ?: "Hello"}, $name${punctuation ?: "."}"

  @JS
  suspend fun greetLater(name: String, greeting: String?): String = "${greeting ?: "Hi"} $name"
}

private fun HermesRuntime.awaitSettled(script: String): String {
  runEventLoop { !evaluate("$script === null").getBool() }
  return evaluateAsString(script)
}

class OptionalArgumentsTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withOptionals(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Optionals())
      block(runtime)
    }
  }

  @Test
  fun `trailing nullable arguments can be left out`() = withOptionals { runtime ->
    assertEquals("Hello, Expo.", runtime.evaluateAsString("expo.modules.Optionals.greet('Expo')"))
    assertEquals("Hey, Expo.", runtime.evaluateAsString("expo.modules.Optionals.greet('Expo', 'Hey')"))
    assertEquals("Hey, Expo!", runtime.evaluateAsString("expo.modules.Optionals.greet('Expo', 'Hey', '!')"))
  }

  @Test
  fun `a constructor's trailing nullable arguments can be left out`() = withOptionals { runtime ->
    assertEquals(
      "main/null/null",
      runtime.evaluateAsString("new expo.modules.Optionals.Connection('main').describe()"),
    )
    assertEquals(
      "main/1/null",
      runtime.evaluateAsString("new expo.modules.Optionals.Connection('main', { a: 1 }).describe()"),
    )
  }

  @Test
  fun `a suspend export's trailing nullable arguments can be left out`() = withOptionals { runtime ->
    runtime.evaluate("globalThis.out = null; expo.modules.Optionals.greetLater('Expo').then((v) => { globalThis.out = v; });")
    assertEquals("Hi Expo", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a non-nullable argument cannot be left out`() = withOptionals { runtime ->
    val message = runtime.evaluateAsString(
      "(() => { try { expo.modules.Optionals.greet(); return 'no-throw'; } catch (e) { return e.message; } })()",
    )
    assertTrue("expects 3 argument(s), but received 0" in message, "unexpected message: $message")
  }

  @Test
  fun `too many arguments are still rejected`() = withOptionals { runtime ->
    val message = runtime.evaluateAsString(
      "(() => { try { expo.modules.Optionals.greet('a', 'b', 'c', 'd'); return 'no-throw'; } " +
        "catch (e) { return e.message; } })()",
    )
    assertTrue("expects 3 argument(s), but received 4" in message, "unexpected message: $message")
  }
}
