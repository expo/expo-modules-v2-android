package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.events.EventSupport
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.testsupport.TestSupport
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.TypeDescriptor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Record
private data class Change(val value: Int, val label: String) : io.github.expo.modules.v2.records.Record

@ExpoSharedObject
private class Player : SharedObject() {
  var starts = 0
  var stops = 0

  @Event
  val onStateChange = event<String>(
    onStartObserving = { starts++ },
    onStopObserving = { stops++ },
  )

  @JS
  fun play(): Int {
    onStateChange("playing")
    return 1
  }
}

/**
 * The declarative form: one module, several events, each payload on a different transport. What
 * each one does is pinned below; the plugin's output for the class is pinned by
 * `testData/box/moduleEvents.kt`.
 */
@ExpoModule(classes = [Player::class])
private class Watcher : Module() {
  var starts = 0
  var stops = 0
  var last: Player? = null

  /** A record payload: rides the buffer. */
  @Event
  val onChanged = event<Change>(
    onStartObserving = { starts++ },
    onStopObserving = { stops++ },
  )

  /** A scalar payload: a JNI slot. */
  @Event
  val onCount = event<Int>()

  /** A string payload: a JNI slot as a result would be, and the overflow path past the buffer. */
  @Event
  val onText = event<String>()

  /** A list payload: the buffer. */
  @Event
  val onNames = event<List<String>>()

  /** A shared object payload: a reference, so JavaScript gets the facade it already holds. */
  @Event
  val onPlayer = event<Player>()

  @JS
  fun fire(value: Int): Int {
    onChanged(Change(value, "v$value"))
    return value
  }

  @JS
  fun emitCount(n: Int) {
    onCount.emit(n)
  }

  @JS
  fun create(): Player = Player().also { last = it }
}

/** A module with no events at all still carries the emitter members. */
@ExpoModule
private class Silent : Module() {
  @JS
  fun noop() = Unit
}

/** Described by hand, in the shape the plugin generates. */
private class Manual : Module() {
  val onChanged = EventSupport.bind(event<Int>(), "changed", TypeDescriptor.Int)
}

class EventEmitterTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun HermesRuntime.watch(module: Watcher = Watcher()): Watcher {
    moduleRegistry.register(module)
    evaluate("globalThis.seen = [];")
    return module
  }

  @Test
  fun `a Kotlin emit inside a host function reaches the listener before the call returns`() {
    HermesRuntime().use { runtime ->
      runtime.watch()

      // The record payload rides the buffer, inside the very host function that carries the call's
      // own arguments and result: the claim is free while the Kotlin body runs.
      assertEquals(
        "2 -> 2:v2",
        runtime.evaluateAsString(
          "expo.modules.Watcher.addListener('changed', (e) => seen.push(e.value + ':' + e.label));" +
            "expo.modules.Watcher.fire(2) + ' -> ' + seen.join()",
        ),
      )
    }
  }

  @Test
  fun `a listener is called with the emitter as this`() {
    HermesRuntime().use { runtime ->
      runtime.watch()
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "expo.modules.Watcher.addListener('count', function () { globalThis.self = this; });" +
            "expo.modules.Watcher.emitCount(1); self === expo.modules.Watcher",
        ),
      )
    }
  }

  @Test
  fun `a Kotlin emit on the JS thread outside any call is delivered at once`() {
    HermesRuntime().use { runtime ->
      val watcher = runtime.watch()
      runtime.evaluate("expo.modules.Watcher.addListener('count', (n) => seen.push(n));")

      // The test thread is this runtime's JS thread, so nothing is posted and nothing is drained.
      watcher.onCount.emit(5)
      watcher.onCount(6)
      assertEquals("5,6", runtime.evaluateAsString("seen.join()"))
    }
  }

  @Test
  fun `a Kotlin emit from another thread is delivered when the JS thread runs`() {
    HermesRuntime().use { runtime ->
      val watcher = runtime.watch()
      runtime.evaluate("expo.modules.Watcher.addListener('count', (n) => seen.push(n));")

      val worker = Thread { watcher.onCount.emit(7) }
      worker.start()
      worker.join()

      // Posted, not delivered: the payload converts on the JS thread, when it drains its jobs.
      assertEquals("0", runtime.evaluateAsString("seen.length"))
      runtime.runEventLoop { runtime.evaluate("seen.length > 0").getBool() }
      assertEquals("7", runtime.evaluateAsString("seen.join()"))
    }
  }

  @Test
  fun `every payload transport reaches JavaScript`() {
    HermesRuntime().use { runtime ->
      val watcher = runtime.watch()
      runtime.evaluate(
        """
        expo.modules.Watcher.addListener('text', (s) => seen.push(s.length));
        expo.modules.Watcher.addListener('names', (l) => seen.push(l.join('|')));
        expo.modules.Watcher.addListener('player', (p) => seen.push(p === globalThis.p));
        globalThis.p = expo.modules.Watcher.create();
        """.trimIndent(),
      )

      watcher.onText.emit("abc")
      // Far past the 256 KB buffer, so the payload takes the overflow slot.
      watcher.onText.emit("x".repeat(300_000))
      watcher.onNames.emit(listOf("a", "b"))
      watcher.onPlayer.emit(requireNotNull(watcher.last))

      assertEquals("3,300000,a|b,true", runtime.evaluateAsString("seen.join()"))
    }
  }

  @Test
  fun `listeners are added, counted and removed as on an EventEmitter`() {
    HermesRuntime().use { runtime ->
      runtime.watch()
      assertEquals(
        "1,2,1,0,1,0",
        runtime.evaluateAsString(
          """
          const w = expo.modules.Watcher;
          const a = () => seen.push('a');
          const b = () => seen.push('b');
          const out = [];
          const sub = w.addListener('count', a);
          out.push(w.listenerCount('count'));
          w.addListener('count', b);
          out.push(w.listenerCount('count'));
          sub.remove();
          out.push(w.listenerCount('count'));
          w.removeListener('count', b);
          out.push(w.listenerCount('count'));
          w.addListener('count', a);
          out.push(w.listenerCount('count'));
          w.removeAllListeners('count');
          out.push(w.listenerCount('count'));
          out.join();
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun `JavaScript can emit too, with the arguments as they are`() {
    HermesRuntime().use { runtime ->
      runtime.watch()
      assertEquals(
        "1|two|3",
        runtime.evaluateAsString(
          "expo.modules.Watcher.addListener('count', (...args) => seen.push(args.join('|')));" +
            "expo.modules.Watcher.emit('count', 1, 'two', 3); seen.join()",
        ),
      )
    }
  }

  @Test
  fun `a throwing listener stops neither the others nor the emitter`() {
    HermesRuntime().use { runtime ->
      val watcher = runtime.watch()
      runtime.evaluate(
        "expo.modules.Watcher.addListener('count', () => { throw new Error('boom'); });" +
          "expo.modules.Watcher.addListener('count', (n) => seen.push(n));",
      )
      watcher.onCount.emit(3)
      assertEquals("3", runtime.evaluateAsString("seen.join()"))
    }
  }

  @Test
  fun `an unknown event name is rejected by every member`() {
    HermesRuntime().use { runtime ->
      runtime.watch()
      runtime.moduleRegistry.register(Silent())

      for (call in listOf(
        "addListener('nope', () => {})",
        "removeListener('nope', () => {})",
        "removeAllListeners('nope')",
        "listenerCount('nope')",
        "emit('nope')",
      )) {
        val error = runtime.evaluateAsString(
          "try { expo.modules.Watcher.$call; 'no error'; } catch (e) { String(e.message); }",
        )
        assertTrue("'nope' is not an event" in error, "$call: $error")
        assertTrue("'changed'" in error && "'count'" in error, "$call names the declared events: $error")
      }

      // A module without events has the members, and says so when asked for anything.
      val silent = runtime.evaluateAsString(
        "try { expo.modules.Silent.addListener('x', () => {}); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue("declares: none" in silent, silent)
    }
  }

  @Test
  fun `one emitter prototype serves every module and shared object of a runtime`() {
    HermesRuntime().use { runtime ->
      runtime.watch()
      runtime.moduleRegistry.register(Silent())

      // A module object's prototype is the emitter; a facade's class prototype inherits from it.
      assertEquals(
        "true,true,true",
        runtime.evaluateAsString(
          """
          const emitter = Object.getPrototypeOf(expo.modules.Watcher);
          const p = expo.modules.Watcher.create();
          [
            Object.getPrototypeOf(expo.modules.Silent) === emitter,
            Object.getPrototypeOf(Object.getPrototypeOf(p)) === emitter,
            Object.getPrototypeOf(new expo.modules.Watcher.Player()) === Object.getPrototypeOf(p),
          ].join()
          """.trimIndent(),
        ),
      )
      // So a member is one function, whatever it is called on.
      assertEquals(
        "true,true",
        runtime.evaluateAsString(
          "[expo.modules.Watcher.addListener === expo.modules.Watcher.create().addListener, " +
            "expo.modules.Watcher.emit === expo.modules.Silent.emit].join()",
        ),
      )
      // And the module's own exports stay its own.
      assertEquals(
        "true,false",
        runtime.evaluateAsString(
          "[expo.modules.Watcher.hasOwnProperty('fire'), expo.modules.Watcher.hasOwnProperty('addListener')].join()",
        ),
      )
    }
  }

  @Test
  fun `the emitter members are not enumerable`() {
    HermesRuntime().use { runtime ->
      runtime.watch()
      assertEquals(
        "false,true",
        runtime.evaluateAsString(
          "[Object.keys(expo.modules.Watcher).includes('addListener'), " +
            "typeof expo.modules.Watcher.addListener === 'function'].join()",
        ),
      )
      assertEquals(
        "false",
        runtime.evaluateAsString(
          "const p = expo.modules.Watcher.create(); const k = []; for (const n in p) k.push(n); k.includes('addListener')",
        ),
      )
    }
  }

  @Test
  fun `the observing hooks fire on the first listener and after the last`() {
    HermesRuntime().use { runtime ->
      val watcher = runtime.watch()

      runtime.evaluate("globalThis.sub = expo.modules.Watcher.addListener('changed', () => {});")
      assertEquals(1 to 0, watcher.starts to watcher.stops)
      assertTrue(watcher.onChanged.isObserved)

      runtime.evaluate("globalThis.fn = () => {}; expo.modules.Watcher.addListener('changed', fn);")
      assertEquals(1 to 0, watcher.starts to watcher.stops, "a second listener starts nothing")

      runtime.evaluate("sub.remove();")
      assertEquals(1 to 0, watcher.starts to watcher.stops, "one listener is left")

      runtime.evaluate("expo.modules.Watcher.removeListener('changed', fn);")
      assertEquals(1 to 1, watcher.starts to watcher.stops)
      assertFalse(watcher.onChanged.isObserved)

      runtime.evaluate("expo.modules.Watcher.addListener('changed', fn); expo.modules.Watcher.removeAllListeners('changed');")
      assertEquals(2 to 2, watcher.starts to watcher.stops)

      // Other events do not count.
      runtime.evaluate("expo.modules.Watcher.addListener('count', fn);")
      assertEquals(2 to 2, watcher.starts to watcher.stops)
    }
  }

  @Test
  fun `closing a runtime that observes stops the observation`() {
    val watcher = Watcher()
    HermesRuntime().use { runtime ->
      runtime.watch(watcher)
      runtime.evaluate("expo.modules.Watcher.addListener('changed', () => {});")
      assertEquals(1, watcher.starts)
    }
    assertEquals(1, watcher.stops)
    assertFalse(watcher.onChanged.isObserved)

    // And a later emit has nowhere to go, which is fine.
    watcher.onChanged.emit(Change(1, "gone"))
  }

  @Test
  fun `two runtimes observing one instance both receive, and count as one observer`() {
    val watcher = Watcher()
    HermesRuntime().use { first ->
      HermesRuntime().use { second ->
        first.watch(watcher)
        second.watch(watcher)

        first.evaluate("globalThis.fn = (n) => seen.push('first:' + n); expo.modules.Watcher.addListener('count', fn);")
        second.evaluate("globalThis.fn = (n) => seen.push('second:' + n); expo.modules.Watcher.addListener('count', fn);")
        assertTrue(watcher.onCount.isObserved)

        // Both runtimes live on this thread, so both deliveries are inline.
        watcher.onCount.emit(4)
        assertEquals("first:4", first.evaluateAsString("seen.join()"))
        assertEquals("second:4", second.evaluateAsString("seen.join()"))

        first.evaluate("expo.modules.Watcher.removeListener('count', fn);")
        assertTrue(watcher.onCount.isObserved, "the second runtime still observes")
        watcher.onCount.emit(5)
        assertEquals("first:4", first.evaluateAsString("seen.join()"))
        assertEquals("second:4,second:5", second.evaluateAsString("seen.join()"))

        second.evaluate("expo.modules.Watcher.removeListener('count', fn);")
        assertFalse(watcher.onCount.isObserved)
      }
    }
  }

  @Test
  fun `a runtime on another thread receives an emit when it drains`() {
    val watcher = Watcher()
    HermesRuntime().use { first ->
      first.watch(watcher)

      val listening = CountDownLatch(1)
      val results = ArrayBlockingQueue<Result<String>>(1)
      val worker = Thread {
        results.put(
          runCatching {
            HermesRuntime().use { second ->
              second.watch(watcher)
              second.evaluate("expo.modules.Watcher.addListener('count', (n) => seen.push(n));")
              listening.countDown()
              second.runEventLoop { second.evaluate("seen.length > 0").getBool() }
              second.evaluateAsString("seen.join()")
            }
          },
        )
      }
      worker.start()

      assertTrue(listening.await(30, TimeUnit.SECONDS), "the second runtime never subscribed")
      // Not this thread's runtime, so the delivery is posted to the worker's loop.
      watcher.onCount.emit(9)

      val onOtherThread = requireNotNull(results.poll(30, TimeUnit.SECONDS)) {
        "the second runtime never answered"
      }.getOrThrow()
      worker.join()
      assertEquals("9", onOtherThread)
    }
  }

  @Test
  fun `a shared object emits on its facade`() {
    HermesRuntime().use { runtime ->
      val watcher = runtime.watch()

      assertEquals(
        "true playing",
        runtime.evaluateAsString(
          "globalThis.p = expo.modules.Watcher.create();" +
            "p.addListener('stateChange', function (s) { seen.push((this === p) + ' ' + s); });" +
            "p.play(); seen.join()",
        ),
      )
      val player = requireNotNull(watcher.last)
      assertEquals(1, player.starts)

      // A second instance has its own listeners.
      assertEquals(
        "0",
        runtime.evaluateAsString("new expo.modules.Watcher.Player().listenerCount('stateChange')"),
      )
      // The members live on the prototype, shared by every instance.
      assertEquals(
        "true",
        runtime.evaluateAsString("p.addListener === new expo.modules.Watcher.Player().addListener"),
      )
    }
  }

  @Test
  fun `releasing a shared object stops its observation and rejects new listeners`() {
    HermesRuntime().use { runtime ->
      val watcher = runtime.watch()
      runtime.evaluate(
        "globalThis.p = expo.modules.Watcher.create();" +
          "p.addListener('stateChange', (s) => seen.push(s)); p.release();",
      )
      val player = requireNotNull(watcher.last)
      assertEquals(1 to 1, player.starts to player.stops)

      // Nothing observes a released object, so an emit goes nowhere.
      player.onStateChange.emit("late")
      assertEquals("0", runtime.evaluateAsString("seen.length"))

      val error = runtime.evaluateAsString(
        "try { p.addListener('stateChange', () => {}); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue("released" in error, error)
    }
  }

  @Test
  fun `a facade collected with listeners still attached stops its observation`() {
    HermesRuntime().use { runtime ->
      TestSupport.install(runtime)
      val watcher = runtime.watch()

      // The script ends in a primitive: an `evaluate` result is a live handle, and one holding the
      // subscription would root the facade through the subscription's `remove`.
      runtime.evaluate("expo.modules.Watcher.create().addListener('stateChange', () => {}); 0")
      val player = requireNotNull(watcher.last)
      assertEquals(1, player.starts)

      // Nothing in JavaScript holds the facade, so collecting it releases the object, which is
      // where the observation ends. It takes more than one pass: the discarded subscription roots
      // the facade through its `remove` until the subscription itself has been finalized, and
      // Hermes may run that finalizer after the collection that found it. The orphaned listeners go
      // the next time the object is looked up.
      var passes = 0
      while (player.stops == 0 && passes < 10) {
        runtime.evaluate("ExpoTestSupport.__collectGarbage(); 0")
        passes++
      }
      assertEquals(1, player.stops, "the facade was not collected in $passes passes")

      player.onStateChange.emit("nobody")
      assertEquals(1, player.stops, "an emit after the facade is gone is a plain no-op")
    }
  }

  @Test
  fun `a hand-described module declares and binds its event itself`() {
    HermesRuntime().use { runtime ->
      val module = Manual()
      runtime.moduleRegistry.register("Manual", module) {
        event("changed", AnyType(TypeDescriptor.Int))
      }

      runtime.evaluate("globalThis.seen = []; expo.modules.Manual.addListener('changed', (n) => seen.push(n));")
      module.onChanged.emit(42)
      assertEquals("42", runtime.evaluateAsString("seen.join()"))
    }
  }

  @Test
  fun `the observation bookkeeping aggregates runtimes`() {
    // No JavaScript here: an AsyncContext stands for a runtime, and nothing has a listener to
    // deliver to. This is the Kotlin half of the hooks in isolation; a runtime's teardown reporting
    // its listeners is pinned by `closing a runtime that observes stops the observation`.
    val watcher = Watcher()
    val first = AsyncContext()
    val second = AsyncContext()
    // The native side names an event by its index in the declaration: `changed` is Watcher's first.
    val changed = 0

    EventSupport.observe(watcher, changed, first, observing = true)
    EventSupport.observe(watcher, changed, second, observing = true)
    EventSupport.observe(watcher, changed, first, observing = true)
    assertEquals(1, watcher.starts)

    EventSupport.observe(watcher, changed, first, observing = false)
    assertEquals(0, watcher.stops)

    EventSupport.observe(watcher, changed, second, observing = false)
    assertEquals(1, watcher.stops)
    assertFalse(watcher.onChanged.isObserved)

    // Stopping what does not observe, or an index of no event, is a no-op.
    EventSupport.observe(watcher, changed, second, observing = false)
    EventSupport.observe(watcher, 99, first, observing = true)
    assertEquals(1 to 1, watcher.starts to watcher.stops)
  }

  @Test
  fun `a hand-described module must declare its events in binding order`() {
    class Swapped : Module() {
      val onFirst = EventSupport.bind(event<Int>(), "first", TypeDescriptor.Int)
      val onSecond = EventSupport.bind(event<Int>(), "second", TypeDescriptor.Int)
    }

    HermesRuntime().use { runtime ->
      val error = assertFailsWith<IllegalArgumentException> {
        runtime.moduleRegistry.register("Swapped", Swapped()) {
          event("second", AnyType(TypeDescriptor.Int))
          event("first", AnyType(TypeDescriptor.Int))
        }
      }
      assertTrue("[second, first]" in error.message!!, error.message)
      assertTrue("[first, second]" in error.message!!, error.message)

      // Same order: fine, and each event is reached by its index.
      val module = Swapped()
      runtime.moduleRegistry.register("Ordered", module) {
        event("first", AnyType(TypeDescriptor.Int))
        event("second", AnyType(TypeDescriptor.Int))
      }
      runtime.evaluate(
        "globalThis.seen = [];" +
          "expo.modules.Ordered.addListener('second', (n) => seen.push('second:' + n));" +
          "expo.modules.Ordered.addListener('first', (n) => seen.push('first:' + n));"
      )
      module.onSecond.emit(2)
      module.onFirst.emit(1)
      assertEquals("second:2,first:1", runtime.evaluateAsString("seen.join()"))
    }
  }
}
