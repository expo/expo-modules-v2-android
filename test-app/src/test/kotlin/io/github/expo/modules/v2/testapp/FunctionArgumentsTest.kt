package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.JavaScriptException
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ExpoSharedObject
private class Ticker @JS constructor(private val start: Int) : SharedObject() {
  @JS
  fun tick(times: Int, onTick: (Int) -> Unit) {
    repeat(times) { onTick(start + it) }
  }
}

@ExpoModule(classes = [Ticker::class])
private class Callbacks : Module() {
  private var stored: ((String) -> String)? = null

  /** The thread JavaScript called [recordThread] on. */
  @Volatile
  var recordedThread: String? = null

  @JS
  fun recordThread() {
    recordedThread = Thread.currentThread().name
  }

  @JS
  fun forEach(values: List<Int>, add: (Int) -> Unit) {
    values.forEach(add)
  }

  @JS
  fun combine(callback: (Int, String) -> String, value: Int): String = callback(value, "x")

  @JS
  fun optional(callback: ((Int) -> Int)?): Int = callback?.invoke(2) ?: -1

  @JS
  fun lists(callback: (List<Int>) -> Int): Int = callback(listOf(1, 2, 3))

  @JS
  fun nullableResult(callback: () -> String?): String = callback() ?: "null"

  @JS
  fun noArguments(callback: () -> Int): Int = callback()

  @JS
  fun throwing(callback: () -> Unit): String =
    try {
      callback()
      "no-throw"
    } catch (e: JavaScriptException) {
      "caught: ${e.message} / ${e.code}"
    }

  @JS
  fun propagating(callback: () -> Unit) = callback()

  @JS
  fun store(callback: (String) -> String) {
    stored = callback
  }

  @JS
  fun fire(argument: String): String = stored?.invoke(argument) ?: "nothing stored"

  @JS
  suspend fun awaiting(callback: suspend (Int) -> Int): Int = callback(5) + 1

  @JS
  suspend fun awaitingUnit(callback: suspend () -> Unit): String {
    callback()
    return "done"
  }

  @JS
  suspend fun awaitingRejection(callback: suspend () -> Int): String =
    try {
      callback()
      "no-throw"
    } catch (e: JavaScriptException) {
      "caught: ${e.message} / ${e.code}"
    }

  /** Calls [callback] from a pool thread; it has to hop back to the JS thread on its own. */
  @JS
  suspend fun fromBackground(callback: suspend (Int) -> Int): String {
    val callerThread = withContext(Dispatchers.Default) {
      val name = Thread.currentThread().name
      callback(1)
      name
    }
    return "$callerThread|$recordedThread"
  }

  /** The lambda starts another async export of this module while this one is still on the stack. */
  @JS
  suspend fun nested(callback: suspend (Int) -> Int): Int = callback(1) + 100

  /** A plain lambda only works on the JS thread. */
  @JS
  suspend fun plainOffThread(callback: (Int) -> Unit): String =
    withContext(Dispatchers.Default) {
      try {
        callback(1)
        "no-throw"
      } catch (e: IllegalStateException) {
        e.message.orEmpty()
      }
    }

  /** A plain lambda in a suspend export is fine for as long as the body is still on the JS thread. */
  @JS
  suspend fun plainOnJsThread(callback: (Int) -> Int): Int = callback(20) + 1
}

class FunctionArgumentsTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withCallbacks(block: (HermesRuntime, Callbacks) -> Unit) {
    HermesRuntime().use { runtime ->
      val module = Callbacks()
      runtime.moduleRegistry.register(module)
      block(runtime, module)
    }
  }

  private fun HermesRuntime.awaitSettled(script: String): String {
    runEventLoop { !evaluate("$script === null").getBool() }
    return evaluateAsString(script)
  }

  /** Runs [call], which has to leave its outcome in `globalThis.out`, and waits for it. */
  private fun HermesRuntime.settle(call: String): String {
    evaluate("globalThis.out = null; $call.then((v) => { globalThis.out = 'ok:' + v; }, (e) => { globalThis.out = 'err:' + e.message; });")
    return awaitSettled("globalThis.out")
  }

  @Test
  fun `a lambda calls the JavaScript function`() = withCallbacks { runtime, _ ->
    assertEquals(
      "1,2,3",
      runtime.evaluateAsString("(() => { const seen = []; expo.modules.Callbacks.forEach([1, 2, 3], (v) => seen.push(v)); return seen.join(','); })()"),
    )
  }

  @Test
  fun `arguments and the result cross in both directions`() = withCallbacks { runtime, _ ->
    assertEquals(
      "7-x!",
      runtime.evaluateAsString("expo.modules.Callbacks.combine((n, s) => n + '-' + s + '!', 7)"),
    )
    assertEquals("6", runtime.evaluateAsString("expo.modules.Callbacks.lists((l) => l.reduce((a, b) => a + b, 0))"))
    assertEquals("42", runtime.evaluateAsString("expo.modules.Callbacks.noArguments(() => 42)"))
  }

  @Test
  fun `a nullable result and a nullable lambda`() = withCallbacks { runtime, _ ->
    assertEquals("null", runtime.evaluateAsString("expo.modules.Callbacks.nullableResult(() => null)"))
    assertEquals("null", runtime.evaluateAsString("expo.modules.Callbacks.nullableResult(() => undefined)"))
    assertEquals("text", runtime.evaluateAsString("expo.modules.Callbacks.nullableResult(() => 'text')"))
    assertEquals("-1", runtime.evaluateAsString("expo.modules.Callbacks.optional(null)"))
    assertEquals("-1", runtime.evaluateAsString("expo.modules.Callbacks.optional()"))
    assertEquals("20", runtime.evaluateAsString("expo.modules.Callbacks.optional((n) => n * 10)"))
  }

  @Test
  fun `a lambda that returns Unit ignores whatever the function returns`() = withCallbacks { runtime, _ ->
    assertEquals(
      "3",
      runtime.evaluateAsString("(() => { let n = 0; expo.modules.Callbacks.forEach([1, 2, 3], () => ++n); return n; })()"),
    )
    assertEquals(
      "3",
      runtime.evaluateAsString("(() => { let n = 0; expo.modules.Callbacks.forEach([1, 2, 3], () => { n++; return 'ignored'; }); return n; })()"),
    )
  }

  @Test
  fun `a lambda can be kept and called after the export returned`() = withCallbacks { runtime, _ ->
    assertEquals("nothing stored", runtime.evaluateAsString("expo.modules.Callbacks.fire('a')"))
    runtime.evaluate("let calls = 0; expo.modules.Callbacks.store((s) => s + (++calls));")
    assertEquals("a1", runtime.evaluateAsString("expo.modules.Callbacks.fire('a')"))
    assertEquals("b2", runtime.evaluateAsString("expo.modules.Callbacks.fire('b')"))
  }

  @Test
  fun `a shared object method takes a lambda`() = withCallbacks { runtime, _ ->
    assertEquals(
      "10,11,12",
      runtime.evaluateAsString(
        "(() => { const seen = []; new expo.modules.Callbacks.Ticker(10).tick(3, (v) => seen.push(v)); return seen.join(','); })()",
      ),
    )
  }

  @Test
  fun `a JavaScript error becomes a JavaScriptException`() = withCallbacks { runtime, _ ->
    assertEquals(
      "caught: boom / E_BOOM",
      runtime.evaluateAsString("expo.modules.Callbacks.throwing(() => { const e = new Error('boom'); e.code = 'E_BOOM'; throw e; })"),
    )
    assertEquals(
      "caught: plain / ",
      runtime.evaluateAsString("expo.modules.Callbacks.throwing(() => { throw new Error('plain'); })"),
    )
    assertEquals("caught: oops / ", runtime.evaluateAsString("expo.modules.Callbacks.throwing(() => { throw 'oops'; })"))
  }

  @Test
  fun `an uncaught JavaScript error reaches the JavaScript caller`() = withCallbacks { runtime, _ ->
    val message = runtime.evaluateAsString(
      "(() => { try { expo.modules.Callbacks.propagating(() => { throw new Error('through'); }); return 'no-throw'; } catch (e) { return e.message; } })()",
    )
    assertEquals("through", message)
  }

  @Test
  fun `a value of the wrong type is an error, not a crash`() = withCallbacks { runtime, _ ->
    val message = runtime.evaluateAsString(
      "(() => { try { expo.modules.Callbacks.noArguments(() => 'not a number'); return 'no-throw'; } catch (e) { return e.message; } })()",
    )
    assertTrue(message != "no-throw", "the call went through")
  }

  @Test
  fun `a value that is not a function is rejected`() = withCallbacks { runtime, _ ->
    val message = runtime.evaluateAsString(
      "(() => { try { expo.modules.Callbacks.noArguments(42); return 'no-throw'; } catch (e) { return e.message; } })()",
    )
    assertTrue("Expected a function" in message, "unexpected message: $message")
    val missing = runtime.evaluateAsString(
      "(() => { try { expo.modules.Callbacks.noArguments(null); return 'no-throw'; } catch (e) { return e.message; } })()",
    )
    assertTrue(missing != "no-throw", "null went through a non-nullable lambda")
  }

  @Test
  fun `a suspend lambda waits for the promise the function returns`() = withCallbacks { runtime, _ ->
    assertEquals("ok:11", runtime.settle("expo.modules.Callbacks.awaiting(async (n) => { await null; return n * 2; })"))
  }

  @Test
  fun `a suspend lambda takes a plain return value too`() = withCallbacks { runtime, _ ->
    assertEquals("ok:6", runtime.settle("expo.modules.Callbacks.awaiting((n) => n)"))
  }

  @Test
  fun `a suspend lambda that returns Unit`() = withCallbacks { runtime, _ ->
    assertEquals(
      "ok:done",
      runtime.settle("expo.modules.Callbacks.awaitingUnit(async () => { await null; })"),
    )
  }

  @Test
  fun `a rejected promise throws in the suspend lambda`() = withCallbacks { runtime, _ ->
    assertEquals(
      "ok:caught: nope / E_NOPE",
      runtime.settle("expo.modules.Callbacks.awaitingRejection(async () => { const e = new Error('nope'); e.code = 'E_NOPE'; throw e; })"),
    )
    assertEquals(
      "ok:caught: reason / ",
      runtime.settle("expo.modules.Callbacks.awaitingRejection(() => Promise.reject('reason'))"),
    )
  }

  @Test
  fun `a rejection nobody catches rejects the export`() = withCallbacks { runtime, _ ->
    assertEquals(
      "err:unhandled",
      runtime.settle("expo.modules.Callbacks.awaiting(async () => { throw new Error('unhandled'); })"),
    )
  }

  @Test
  fun `a suspend lambda called from another thread runs on the JS thread`() = withCallbacks { runtime, _ ->
    val jsThread = Thread.currentThread().name
    val outcome = runtime.settle(
      "expo.modules.Callbacks.fromBackground((n) => { expo.modules.Callbacks.recordThread(); return n; })",
    )
    // The coroutines debug agent appends " @coroutine#N" to the name of a thread running one.
    val (callerThread, recorded) = outcome.removePrefix("ok:").split("|").map { it.substringBefore(" @") }
    assertNotEquals(jsThread, callerThread, "the body never left the JS thread")
    assertEquals(jsThread, recorded, "the function ran off the JS thread")
  }

  @Test
  fun `a function may start another async export from inside a suspend lambda`() = withCallbacks { runtime, _ ->
    assertEquals(
      "ok:107",
      runtime.settle("expo.modules.Callbacks.nested((n) => expo.modules.Callbacks.awaiting((m) => m + n))"),
    )
    // The same, with a function that answers at once instead of returning the inner promise.
    assertEquals(
      "ok:101",
      runtime.settle("expo.modules.Callbacks.nested((n) => { expo.modules.Callbacks.awaiting((m) => m); return n; })"),
    )
  }

  @Test
  fun `a plain lambda throws when called from another thread`() = withCallbacks { runtime, _ ->
    val outcome = runtime.settle("expo.modules.Callbacks.plainOffThread((n) => {})")
    assertTrue("JavaScript thread" in outcome, "unexpected outcome: $outcome")
  }

  @Test
  fun `a plain lambda works in a suspend export while it is on the JS thread`() = withCallbacks { runtime, _ ->
    assertEquals("ok:41", runtime.settle("expo.modules.Callbacks.plainOnJsThread((n) => n * 2)"))
  }
}
