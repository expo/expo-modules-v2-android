package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.async.JSScheduler
import io.github.expo.modules.v2.async.Promise
import io.github.expo.modules.v2.async.resolve
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.types.TypeDescriptor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Settles every promise it hands out from a thread of its own, after the export returned. */
@ExpoSharedObject
private class Doubler @JS constructor(private val value: Int) : SharedObject() {
  @JS
  fun result(): Promise<Int> {
    val promise = Promise<Int>()
    thread { promise.resolve(value * 2) }
    return promise
  }
}

/**
 * Synchronous exports that return a `Promise<T>`, covering when the body settles it (before
 * returning, or later from another thread), how (resolve, reject, throw), and what it carries.
 */
@ExpoModule(name = "Promises", classes = [Doubler::class])
private class PromisesModule : Module() {
  @JS
  fun immediate(value: Int): Promise<String> = Promise<String>().apply { resolve("immediate:$value") }

  @JS
  fun later(value: Int): Promise<String> {
    val promise = Promise<String>()
    thread {
      Thread.sleep(5)
      promise.resolve("later:$value")
    }
    return promise
  }

  @JS
  fun rejected(): Promise<String> = Promise<String>().apply { reject(IllegalStateException("kaboom")) }

  /** Throws instead of returning, so there is no Kotlin promise at all. */
  @JS
  fun boom(): Promise<String> = throw IllegalArgumentException("before any promise")

  @JS
  fun settledTwice(): Promise<String> = Promise<String>().apply {
    resolve("first")
    resolve("second")
    reject(IllegalStateException("too late"))
  }

  /** A buffered argument and a buffered result, resolved off the JS thread. */
  @JS
  fun doubled(values: List<Int>): Promise<List<Int>> {
    val promise = Promise<List<Int>>()
    thread { promise.resolve(values.map { it * 2 }) }
    return promise
  }

  @JS
  fun maybe(present: Boolean): Promise<String?> =
    Promise<String?>().apply { resolve(if (present) "here" else null) }

  /** Every scalar crosses as the JavaScript value it becomes, without a JNI unboxing call. */
  @JS
  fun flag(value: Boolean): Promise<Boolean> = Promise.resolve(!value)

  @JS
  fun wide(value: Long): Promise<Long> = Promise.resolve(value * 2)

  @JS
  fun half(value: Double): Promise<Double> = Promise.resolve(value / 2)

  @JS
  fun narrow(value: Float): Promise<Float> = Promise.resolve(value * 2)

  @JS
  fun nothing(): Promise<Unit> {
    val promise = Promise<Unit>()
    thread { promise.resolve() }
    return promise
  }

  /** Too big for the shared buffer, so the result takes the overflow slot instead. */
  @JS
  @BufferMode(returns = Buffer.YES)
  fun huge(size: Int): Promise<String> = Promise<String>().apply { resolve("x".repeat(size)) }

  private val sharedPromise = Promise<String>()

  @JS
  fun shared(): Promise<String> = sharedPromise

  @JS
  fun settleShared(value: String) {
    sharedPromise.resolve(value)
  }

  private val nullLater = Promise<String?>()

  /** Pending when returned; [settleNullLater] resolves it with `null` once JavaScript follows it. */
  @JS
  fun nullLater(): Promise<String?> = nullLater

  @JS
  fun settleNullLater() {
    nullLater.resolve(null)
  }

  @JS
  fun executor(value: Int): Promise<String> = Promise { resolve, _ -> resolve("executor:$value") }

  @JS
  fun executorLater(value: Int): Promise<String> = Promise { resolve, _ ->
    thread { resolve("executor-later:$value") }
  }

  @JS
  fun executorRejects(): Promise<String> = Promise { _, reject ->
    thread { reject(IllegalStateException("from executor")) }
  }

  @JS
  fun executorThrows(): Promise<String> = Promise { _, _ -> throw IllegalArgumentException("executor threw") }

  @JS
  fun alreadyResolved(value: Int): Promise<Int> = Promise.resolve(value * 2)

  @JS
  fun alreadyRejected(): Promise<Int> = Promise.reject(UnsupportedOperationException("nope"))

  @JS
  fun alreadyNothing(): Promise<Unit> = Promise.resolve()

  /** Never settled by anything in JavaScript's reach. */
  val pending = Promise<String>()

  @JS
  fun forever(): Promise<String> = pending
}

/** Runs the JS thread until [script] stops evaluating to `null`, then returns it as a string. */
private fun HermesRuntime.awaitSettled(script: String): String {
  runEventLoop { !evaluate("($script) === null").getBool() }
  return evaluateAsString(script)
}

class PromiseTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withPromises(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(PromisesModule())
      block(runtime)
    }
  }

  @Test
  fun `a promise settled before returning settles inside the call`() = withPromises { runtime ->
    assertEquals(
      "[object Promise]",
      runtime.evaluateAsString(
        "globalThis.out = null;" +
          "globalThis.p = expo.modules.Promises.immediate(7);" +
          "globalThis.p.then((v) => { globalThis.out = v; });" +
          "Object.prototype.toString.call(globalThis.p)",
      ),
    )
    // The same fast path as a `suspend` body that never suspends: no job, only the `.then`.
    assertFalse(runtime.drainJobs(), "the settle went through the job queue instead of the call")
    assertEquals("immediate:7", runtime.evaluateAsString("globalThis.out"))
  }

  @Test
  fun `a promise settled from another thread settles on the JS thread`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "expo.modules.Promises.later(2).then((v) => { globalThis.out = v; });",
    )
    assertEquals("later:2", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `reject carries the throwable's code, message and stack`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "expo.modules.Promises.rejected().catch((e) => {" +
        "  globalThis.out = e.code + '/' + e.message + '/' + (typeof e.nativeStack);" +
        "});",
    )
    assertEquals(
      "java.lang.IllegalStateException/kaboom/string",
      runtime.awaitSettled("globalThis.out"),
    )
  }

  @Test
  fun `a body that throws before returning rejects instead of throwing`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "try {" +
        "  expo.modules.Promises.boom().catch((e) => { globalThis.out = e.code + '|' + e.message; });" +
        "} catch (e) {" +
        "  globalThis.out = 'threw synchronously';" +
        "}",
    )
    assertEquals(
      "java.lang.IllegalArgumentException|before any promise",
      runtime.awaitSettled("globalThis.out"),
    )
  }

  @Test
  fun `only the first settle counts`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "expo.modules.Promises.settledTwice()" +
        "  .then((v) => { globalThis.out = v; }, (e) => { globalThis.out = 'rejected'; });",
    )
    assertEquals("first", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `results cross on every transport`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.a = null;" +
        "expo.modules.Promises.doubled([1, 2, 3]).then((v) => { globalThis.a = v.join(','); });",
    )
    assertEquals("2,4,6", runtime.awaitSettled("globalThis.a"))

    runtime.evaluate(
      "globalThis.b = null;" +
        "expo.modules.Promises.maybe(true).then((v) => { globalThis.b = String(v); });" +
        "expo.modules.Promises.maybe(false).then((v) => { globalThis.b += ',' + String(v); });",
    )
    assertEquals("here,null", runtime.awaitSettled("globalThis.b"))

    runtime.evaluate(
      "globalThis.n = null;" +
        "expo.modules.Promises.nullLater().then((v) => { globalThis.n = String(v); });" +
        "expo.modules.Promises.settleNullLater();",
    )
    assertEquals("null", runtime.awaitSettled("globalThis.n"))

    runtime.evaluate(
      "globalThis.s = [];" +
        "const P = expo.modules.Promises;" +
        "P.flag(true).then((v) => { s[0] = typeof v + ':' + v; });" +
        "P.wide(4000000000).then((v) => { s[1] = v; });" +
        "P.half(5).then((v) => { s[2] = v; });" +
        "P.narrow(1.5).then((v) => { s[3] = v; });",
    )
    assertEquals(
      "boolean:false,8000000000,2.5,3",
      runtime.awaitSettled("s.length === 4 ? s.join(',') : null"),
    )

    // Unit resolves with undefined rather than leaving the promise pending.
    runtime.evaluate(
      "globalThis.c = null;" +
        "expo.modules.Promises.nothing().then((v) => { globalThis.c = (v === undefined); });",
    )
    assertEquals("true", runtime.awaitSettled("globalThis.c"))

    // Far past the 256 KB buffer, so the result takes the overflow slot.
    runtime.evaluate(
      "globalThis.d = null;" +
        "expo.modules.Promises.huge(300000).then((v) => { globalThis.d = v.length; });",
    )
    assertEquals("300000", runtime.awaitSettled("globalThis.d"))
  }

  @Test
  fun `one promise returned from several calls settles every one of them`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = [];" +
        "expo.modules.Promises.shared().then((v) => { out.push('a:' + v); });" +
        "expo.modules.Promises.shared().then((v) => { out.push('b:' + v); });" +
        "expo.modules.Promises.settleShared('done');" +
        // Handed out after it settled, so it is born settled.
        "expo.modules.Promises.shared().then((v) => { out.push('c:' + v); });",
    )
    assertEquals(
      "a:done,b:done,c:done",
      runtime.awaitSettled("out.length === 3 ? out.slice().sort().join(',') : null"),
    )
  }

  /**
   * Settling and subscribing race on the two fields of a [Promise]; a subscriber that joins while
   * the promise settles must still be settled. Through JavaScript the window is a few instructions
   * wide and no number of calls hits it, so this drives both sides directly, on two threads that
   * start every promise together.
   */
  @Test
  fun `a subscriber joining while the promise settles is never left pending`() {
    val posted = AtomicInteger()
    val context = AsyncContext(
      object : JSScheduler {
        override val isOnJSThread = false

        override fun post(job: Runnable) {
          posted.incrementAndGet()
        }
      },
    )
    val count = 200_000
    val promises = List(count) { Promise<Int>() }
    val handles = List(count) { context.createPromise(it.toLong()) }

    val settlerAt = AtomicInteger(-1)
    val subscriberAt = AtomicInteger(-1)
    fun inStep(mine: AtomicInteger, theirs: AtomicInteger, index: Int) {
      mine.set(index)
      while (theirs.get() < index) {
        Thread.onSpinWait()
      }
    }
    val settler = thread {
      for (index in 0 until count) {
        inStep(settlerAt, subscriberAt, index)
        promises[index].resolve(index)
      }
    }
    val subscriber = thread {
      for (index in 0 until count) {
        inStep(subscriberAt, settlerAt, index)
        handles[index].subscribeTo(promises[index], TypeDescriptor.Int, false)
      }
    }
    settler.join()
    subscriber.join()

    // A handle settles once, so this only gets through to one that nothing settled.
    posted.set(0)
    handles.forEach { it.resolve(-1, TypeDescriptor.Int, false) }
    assertEquals(0, posted.get(), "handles left pending")
    context.invalidate()
  }

  @Test
  fun `a shared object method returns a promise`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "new expo.modules.Promises.Doubler(3).result().then((v) => { globalThis.out = v; });",
    )
    assertEquals("6", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `an executor that resolves right away settles inside the call`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "expo.modules.Promises.executor(3).then((v) => { globalThis.out = v; });",
    )
    assertFalse(runtime.drainJobs(), "the settle went through the job queue instead of the call")
    assertEquals("executor:3", runtime.evaluateAsString("globalThis.out"))
  }

  @Test
  fun `an executor may settle later from another thread`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "expo.modules.Promises.executorLater(4).then((v) => { globalThis.out = v; });",
    )
    assertEquals("executor-later:4", runtime.awaitSettled("globalThis.out"))

    runtime.evaluate(
      "globalThis.err = null;" +
        "expo.modules.Promises.executorRejects()" +
        "  .catch((e) => { globalThis.err = e.code + '|' + e.message; });",
    )
    assertEquals(
      "java.lang.IllegalStateException|from executor",
      runtime.awaitSettled("globalThis.err"),
    )
  }

  @Test
  fun `an executor that throws rejects`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "expo.modules.Promises.executorThrows()" +
        "  .catch((e) => { globalThis.out = e.code + '|' + e.message; });",
    )
    assertEquals(
      "java.lang.IllegalArgumentException|executor threw",
      runtime.awaitSettled("globalThis.out"),
    )
  }

  @Test
  fun `Promise resolve and reject are born settled`() = withPromises { runtime ->
    runtime.evaluate(
      "globalThis.out = [];" +
        "expo.modules.Promises.alreadyResolved(21).then((v) => { out.push('resolved:' + v); });" +
        "expo.modules.Promises.alreadyRejected()" +
        "  .catch((e) => { out.push('rejected:' + e.code + '|' + e.message); });" +
        "expo.modules.Promises.alreadyNothing().then((v) => { out.push('nothing:' + v); });",
    )
    assertFalse(runtime.drainJobs(), "a settle went through the job queue instead of the call")
    assertEquals(
      "nothing:undefined,rejected:java.lang.UnsupportedOperationException|nope,resolved:42",
      runtime.evaluateAsString("out.slice().sort().join(',')"),
    )
  }

  @Test
  fun `settling after the runtime closed does nothing`() {
    val module = PromisesModule()
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(module)
      runtime.evaluate("globalThis.stuck = expo.modules.Promises.forever();")
      runtime.drainJobs()
    }
    // The handle it was returned as outlived its runtime; settling it must reach nothing.
    module.pending.resolve("too late")
  }
}
