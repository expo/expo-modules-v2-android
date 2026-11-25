package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.JavaScriptException
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.async.Promise
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

  /** A plain lambda called from a pool thread blocks it until the call is done on the JS thread. */
  @JS
  suspend fun plainOffThread(callback: (Int) -> Int): String =
    withContext(Dispatchers.Default) {
      val name = Thread.currentThread().name
      val result = callback(1)
      "$name|$recordedThread|$result"
    }

  /** The lambda hands back the JavaScript promise without waiting for it. */
  @JS
  fun promised(callback: (Int) -> Promise<Int>): Promise<Int> = callback(4)

  /** Returns as soon as the function did, whatever its promise does. */
  @JS
  fun promisedReturns(callback: () -> Promise<Int>): String {
    callback()
    return "returned"
  }

  /** A `Promise` lambda called from a pool thread still waits for the function to return. */
  @JS
  suspend fun promisedOffThread(callback: () -> Promise<Int>): String =
    withContext(Dispatchers.Default) {
      callback()
      "$recordedThread"
    }

  @JS
  suspend fun promisedSuspend(callback: suspend () -> Promise<Int>): String =
    withContext(Dispatchers.Default) {
      callback()
      "$recordedThread"
    }

  private var storedPlain: (() -> Int)? = null

  @JS
  fun storePlain(callback: () -> Int) {
    storedPlain = callback
  }

  /** Calls the stored lambda from Kotlin, outside any export. */
  fun callStoredPlain(): Int = checkNotNull(storedPlain)()

  /** A plain lambda in a suspend export is fine for as long as the body is still on the JS thread. */
  @JS
  suspend fun plainOnJsThread(callback: (Int) -> Int): Int = callback(20) + 1

  private var storedSuspend: (suspend () -> Int)? = null

  @JS
  fun storeSuspend(callback: suspend () -> Int) {
    storedSuspend = callback
  }

  /** Calls the stored lambda from Kotlin, outside any export: nothing cancels it but its own caller. */
  suspend fun callStoredSuspend(): Int = checkNotNull(storedSuspend)()
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
  fun `a rejection whose reason throws from a getter still rejects`() = withCallbacks { runtime, _ ->
    assertEquals(
      "ok:caught: bad / ",
      runtime.settle(
        "expo.modules.Callbacks.awaitingRejection(() => " +
          "Promise.reject({ get code() { throw new Error('getter'); }, message: 'bad' }))",
      ),
    )
  }

  @Test
  fun `a thenable that resolves and then throws resolves`() = withCallbacks { runtime, _ ->
    assertEquals(
      "ok:8",
      runtime.settle(
        "expo.modules.Callbacks.awaiting(() => ({ then(resolve) { resolve(7); throw new Error('late'); } }))",
      ),
    )
  }

  @Test
  fun `a thenable that throws and then resolves rejects`() = withCallbacks { runtime, _ ->
    assertEquals(
      "ok:caught: early / ",
      runtime.settle(
        "expo.modules.Callbacks.awaitingRejection(() => ({ then(resolve) { " +
          "Promise.resolve().then(() => resolve(1)); throw new Error('early'); } }))",
      ),
    )
  }

  @Test
  fun `a suspend lambda waiting on a promise fails when the runtime closes`() {
    val runtime = HermesRuntime()
    val module = Callbacks()
    runtime.moduleRegistry.register(module)
    runtime.evaluate(
      "expo.modules.Callbacks.storeSuspend(() => { globalThis.called = true; return new Promise(() => {}); })",
    )

    val pending = CoroutineScope(Dispatchers.Default).async { module.callStoredSuspend() }
    runtime.runEventLoop { runtime.evaluate("globalThis.called === true").getBool() }
    runtime.close()

    val failure = runBlocking { withTimeout(5.seconds) { runCatching { pending.await() }.exceptionOrNull() } }
    // Not a timeout, which is an IllegalStateException too: the call has to fail on its own.
    assertIs<IllegalStateException>(failure)
    assertEquals("The JavaScript runtime this function belongs to was closed", failure.message)
  }

  @Test
  fun `a suspend lambda called after the runtime closed fails instead of waiting`() {
    val runtime = HermesRuntime()
    val module = Callbacks()
    runtime.moduleRegistry.register(module)
    runtime.evaluate("expo.modules.Callbacks.storeSuspend(() => 1)")
    runtime.close()

    val failure = runBlocking(Dispatchers.Default) {
      withTimeout(5.seconds) { runCatching { module.callStoredSuspend() }.exceptionOrNull() }
    }
    // Not a timeout, which is an IllegalStateException too: the call has to fail on its own.
    assertIs<IllegalStateException>(failure)
    assertEquals("The JavaScript runtime this function belongs to was closed", failure.message)
  }

  @Test
  fun `a lambda belongs to its own runtime, not the one the thread made last`() = withCallbacks { runtime, _ ->
    // A second runtime on this thread takes the thread's current-runtime slot, and closing it
    // empties the slot.
    HermesRuntime().close()
    assertEquals("ok:11", runtime.settle("expo.modules.Callbacks.awaiting(async (n) => n * 2)"))
    assertEquals("6", runtime.evaluateAsString("String(expo.modules.Callbacks.noArguments(() => 6))"))
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
  fun `a plain lambda called from another thread blocks until the JS thread ran it`() = withCallbacks { runtime, _ ->
    val jsThread = Thread.currentThread().name
    val outcome = runtime.settle(
      "expo.modules.Callbacks.plainOffThread((n) => { expo.modules.Callbacks.recordThread(); return n + 1; })",
    )
    val (callerThread, recorded, result) = outcome.removePrefix("ok:").split("|").map { it.substringBefore(" @") }
    assertNotEquals(jsThread, callerThread, "the body never left the JS thread")
    assertEquals(jsThread, recorded, "the function ran off the JS thread")
    assertEquals("2", result)
  }

  @Test
  fun `a plain lambda called from another thread awaits the promise`() = withCallbacks { runtime, _ ->
    val outcome = runtime.settle(
      "expo.modules.Callbacks.plainOffThread(async (n) => { await null; expo.modules.Callbacks.recordThread(); return n * 10; })",
    )
    assertEquals("10", outcome.substringAfterLast("|"))
  }

  @Test
  fun `a plain lambda on the JS thread awaits a promise its microtasks settle`() = withCallbacks { runtime, _ ->
    assertEquals(
      "42",
      runtime.evaluateAsString("String(expo.modules.Callbacks.noArguments(async () => { await null; await null; return 42; }))"),
    )
    assertEquals(
      "caught: late / E_LATE",
      runtime.evaluateAsString(
        "expo.modules.Callbacks.throwing(async () => { await null; const e = new Error('late'); e.code = 'E_LATE'; throw e; })",
      ),
    )
  }

  @Test
  fun `a plain lambda on the JS thread fails on a promise that cannot settle yet`() = withCallbacks { runtime, _ ->
    val outcome = runtime.evaluateAsString("expo.modules.Callbacks.throwing(() => new Promise(() => {}))")
    assertTrue("suspend" in outcome && outcome.startsWith("caught:"), "unexpected outcome: $outcome")
  }

  @Test
  fun `a plain lambda called after the runtime closed fails instead of blocking`() {
    val runtime = HermesRuntime()
    val module = Callbacks()
    runtime.moduleRegistry.register(module)
    runtime.evaluate("expo.modules.Callbacks.storePlain(() => 1)")
    runtime.close()

    val failure = runBlocking(Dispatchers.Default) {
      withTimeout(5.seconds) { runCatching { module.callStoredPlain() }.exceptionOrNull() }
    }
    assertIs<IllegalStateException>(failure)
    assertEquals("The JavaScript runtime this function belongs to was closed", failure.message)
  }

  @Test
  fun `a lambda that returns a Promise hands over the JavaScript one`() = withCallbacks { runtime, _ ->
    assertEquals("ok:12", runtime.settle("expo.modules.Callbacks.promised(async (n) => { await null; return n * 3; })"))
    assertEquals("ok:5", runtime.settle("expo.modules.Callbacks.promised((n) => n + 1)"))
    assertEquals(
      "err:refused",
      runtime.settle("expo.modules.Callbacks.promised(async () => { throw new Error('refused'); })"),
    )
  }

  @Test
  fun `a lambda that returns a Promise does not wait for it`() = withCallbacks { runtime, _ ->
    assertEquals("returned", runtime.evaluateAsString("expo.modules.Callbacks.promisedReturns(() => new Promise(() => {}))"))
  }

  @Test
  fun `a lambda that returns a Promise waits for the function off the JS thread`() = withCallbacks { runtime, _ ->
    val jsThread = Thread.currentThread().name
    val script = "(() => { expo.modules.Callbacks.recordThread(); return new Promise(() => {}); })"
    assertEquals(jsThread, runtime.settle("expo.modules.Callbacks.promisedOffThread($script)").removePrefix("ok:"))
    assertEquals(jsThread, runtime.settle("expo.modules.Callbacks.promisedSuspend($script)").removePrefix("ok:"))
  }

  @Test
  fun `a plain lambda works in a suspend export while it is on the JS thread`() = withCallbacks { runtime, _ ->
    assertEquals("ok:41", runtime.settle("expo.modules.Callbacks.plainOnJsThread((n) => n * 2)"))
  }
}
