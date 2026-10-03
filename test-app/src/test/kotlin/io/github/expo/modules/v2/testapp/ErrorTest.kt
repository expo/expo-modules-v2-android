package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.JavaScriptThrowable
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

private class Refusal(override val code: String, message: String) : JavaScriptThrowable(message)

/** Names no code, so its error has no `code` property. */
private class Unnamed : JavaScriptThrowable("unnamed failure")

/** Gives JavaScript another message than the one Kotlin sees. */
private class Rephrased : JavaScriptThrowable("internal detail") {
  override val message: String get() = "rephrased for JavaScript"
  override val code: String get() = "ERR_REPHRASED"
}

/** Has no message, so JavaScript gets its description. */
private class Messageless : JavaScriptThrowable()

@ExpoSharedObject
private class Fragile : SharedObject() {
  @JS
  fun crack(): Int = throw IllegalStateException("cracked")
}

@ExpoModule(classes = [Fragile::class])
private class Failing : Module() {
  @JS
  fun plain(): Int = throw IllegalStateException("plain failure")

  @JS
  fun plainString(): String = throw IllegalArgumentException("plain string failure")

  @JS
  fun fragile(): Fragile = Fragile()

  @JS
  fun coded(): Int = throw Refusal("ERR_REFUSED", "refused")

  @JS
  suspend fun codedLater(): Int = throw Refusal("ERR_REFUSED_LATER", "refused later")

  @JS
  fun unnamed(): Int = throw Unnamed()

  @JS
  suspend fun unnamedLater(): Int = throw Unnamed()

  @JS
  fun rephrased(): Int = throw Rephrased()

  @JS
  fun messageless(): Int = throw Messageless()
}

private fun HermesRuntime.awaitSettled(script: String): String {
  runEventLoop { !evaluate("$script === null").getBool() }
  return evaluateAsString(script)
}

/** The fields of the error a call throws, as `code|message|isError`. */
private fun HermesRuntime.errorOf(call: String): String = evaluateAsString(
  "(() => { try { $call; return 'no-throw'; } " +
    "catch (e) { return e.code + '|' + e.message + '|' + (e instanceof Error); } })()",
)

/** Whether the error a call throws has a `code` property at all. */
private fun HermesRuntime.hasCode(call: String): String = evaluateAsString(
  "(() => { try { $call; return 'no-throw'; } catch (e) { return String('code' in e); } })()",
)

class ErrorTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withFailing(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Failing())
      block(runtime)
    }
  }

  @Test
  fun `a sync export throws an Error with the exception's class as its code and its message`() =
    withFailing { runtime ->
      assertEquals(
        "java.lang.IllegalStateException|plain failure|true",
        runtime.errorOf("expo.modules.Failing.plain()"),
      )
      assertEquals(
        "java.lang.IllegalArgumentException|plain string failure|true",
        runtime.errorOf("expo.modules.Failing.plainString()"),
      )
    }

  @Test
  fun `a shared object method throws the same kind of Error`() = withFailing { runtime ->
    assertEquals(
      "java.lang.IllegalStateException|cracked|true",
      runtime.errorOf("expo.modules.Failing.fragile().crack()"),
    )
  }

  @Test
  fun `a sync error carries the Kotlin stack beside the JavaScript one`() = withFailing { runtime ->
    assertEquals(
      "true",
      runtime.evaluateAsString(
        "(() => { try { expo.modules.Failing.plain(); } " +
          "catch (e) { return e.nativeStack.includes('IllegalStateException'); } })()",
      ),
    )
  }

  @Test
  fun `a sync export throwing a JavaScriptThrowable reports its code`() = withFailing { runtime ->
    assertEquals("ERR_REFUSED|refused|true", runtime.errorOf("expo.modules.Failing.coded()"))
  }

  @Test
  fun `a suspend export throwing a JavaScriptThrowable rejects with its code`() = withFailing { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "expo.modules.Failing.codedLater().catch((e) => { globalThis.out = e.code + '|' + e.message; });",
    )
    assertEquals("ERR_REFUSED_LATER|refused later", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a JavaScriptThrowable without a code throws an Error with no code`() = withFailing { runtime ->
    assertEquals("false", runtime.hasCode("expo.modules.Failing.unnamed()"))
    assertEquals("undefined|unnamed failure|true", runtime.errorOf("expo.modules.Failing.unnamed()"))
  }

  @Test
  fun `a suspend export throwing a JavaScriptThrowable without a code rejects with no code`() =
    withFailing { runtime ->
      runtime.evaluate(
        "globalThis.out = null;" +
          "expo.modules.Failing.unnamedLater().catch((e) => { globalThis.out = ('code' in e) + '|' + e.message; });",
      )
      assertEquals("false|unnamed failure", runtime.awaitSettled("globalThis.out"))
    }

  @Test
  fun `a JavaScriptThrowable can give JavaScript its own message`() = withFailing { runtime ->
    assertEquals(
      "ERR_REPHRASED|rephrased for JavaScript|true",
      runtime.errorOf("expo.modules.Failing.rephrased()"),
    )
  }

  @Test
  fun `a JavaScriptThrowable without a message reports its description`() = withFailing { runtime ->
    assertEquals(
      "undefined|${Messageless::class.java.name}|true",
      runtime.errorOf("expo.modules.Failing.messageless()"),
    )
  }
}
