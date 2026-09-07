package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.sharedobjects.SharedObjectRegistry
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.testsupport.TestSupport
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.TypeDescriptor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A shared object: one Kotlin instance JavaScript holds a reference to, rather than a value copied
 * across the bridge.
 *
 * Described by hand here, in the shape the compiler plugin will generate — the same arrangement
 * [HermesRuntimeTest]'s trampoline fixtures use.
 */
private class Counter : io.github.expo.modules.v2.SharedObject() {
  private var count: Int = 0

  var releaseCount: Int = 0
    private set

  fun increment(): Int = ++count

  fun getValue(): Int = count

  fun setValue(value: Int) {
    count = value
  }

  override fun sharedObjectDidRelease() {
    releaseCount++
  }
}

/** A second class, so a declared parameter type can be shown to reject the wrong one. */
private class Tag : io.github.expo.modules.v2.SharedObject() {
  fun label(): String = "tag"
}

private class CounterModule : Module() {
  /**
   * The module holds the instance, so JavaScript can be handed the *same* native object twice and
   * identity can be asserted.
   */
  var last: Counter? = null
    private set

  fun create(): Counter = Counter().also { last = it }

  fun current(): Counter = requireNotNull(last)

  fun bumpTwice(counter: Counter): Int {
    counter.increment()
    return counter.increment()
  }
}

private class TagModule : Module() {
  fun create(): Tag = Tag()
}

@ExpoSharedObject
private class Greeter(private val text: String) : SharedObject() {
  @JS fun greet(name: String): String = "$text, $name!"

  @JS val greeting: String get() = text
}

/**
 * The declarative form: a `@ExpoSharedObject` class nested inside a module is exposed on it without
 * being listed, because nesting already says which module owns it.
 */
@ExpoModule
private class Widgets : Module() {
  @ExpoSharedObject
  class Label(private val text: String) : SharedObject() {
    @JS
    fun render(): String = "[$text]"
  }

  /**
   * Nested and shared, but its only constructor is private, so JavaScript gets no class object.
   * That is how a shared object opts out of `new` now that a sole public constructor is taken as
   * the one to expose.
   */
  @ExpoSharedObject
  class Hidden private constructor() : SharedObject() {
    @JS
    fun name(): String = "hidden"

    companion object {
      fun create(): Hidden = Hidden()
    }
  }

  @JS
  fun renderOf(label: Label): String = label.render()

  @JS
  fun hidden(): Hidden = Hidden.create()
}

/** Declared outside any module, so the module has to list it. */
@ExpoSharedObject(name = "Renamed")
private class Badge(private val n: Int) : SharedObject() {
  @JS
  fun value(): Int = n
}

@ExpoModule(classes = [Badge::class])
private class Badges : Module()

@Record
private data class Origin(val x: Int, val y: Int) : io.github.expo.modules.v2.records.Record

@ExpoSharedObject
private class Marker(
  private val count: Int,
  private val origin: Origin,
  private val tags: List<String>,
) : SharedObject() {
  @JS
  fun describe(): String = "$count@${origin.x},${origin.y}:${tags.joinToString("|")}"
}

@ExpoModule(classes = [Marker::class])
private class Markers : Module() {
  @JS
  fun describeOf(marker: Marker): String = marker.describe()
}

@ExpoSharedObject
private class Window(private val span: kotlin.time.Duration) : SharedObject() {
  @JS
  fun millis(): Int = span.inWholeMilliseconds.toInt()
}

@ExpoModule(classes = [Window::class])
private class Windows : Module()

@ExpoModule(classes = [Greeter::class])
private class Greeters : Module() {
  private var last: Greeter? = null

  @JS
  fun remember(greeter: Greeter): String {
    last = greeter
    return greeter.greeting
  }

  @JS
  fun last(): Greeter = requireNotNull(last)

  fun lastOrNull(): Greeter? = last
}

/**
 * The same thing again, declared with `@ExpoSharedObject` instead of by hand — what an author actually
 * writes. The plugin fills the same [io.github.expo.modules.v2.modules.ModuleBuilder] for both
 * bases, and adds the `ExpoSharedObject` supertype this class never names.
 */
@ExpoSharedObject
private class Timer : SharedObject() {
  @JS var seconds: Int = 0

  @JS fun tick(): Int = ++seconds

  @JS fun describe(prefix: String): String = "$prefix$seconds"
}

/** A `SharedRef`: an opaque handle to a native value, exposing nothing of the value itself. */
@ExpoSharedObject
private class TextRef(text: StringBuilder) : SharedRef<StringBuilder>(text) {
  @JS fun length(): Int = ref.length
}

@ExpoSharedObject
private class NumberRef(number: java.util.concurrent.atomic.AtomicInteger) :
  SharedRef<java.util.concurrent.atomic.AtomicInteger>(number)

@ExpoModule
private class Refs : Module() {
  @JS fun text(value: String): TextRef = TextRef(StringBuilder(value))

  @JS fun number(): NumberRef = NumberRef(java.util.concurrent.atomic.AtomicInteger(7))

  /** Declared as the concrete subclass: the native encoder checks it exactly. */
  @JS fun readText(ref: TextRef): String = ref.ref.toString()

  /** Declared as `SharedRef<T>`: only Kotlin can see what the ref carries. */
  @JS fun readAny(ref: SharedRef<StringBuilder>): String = ref.ref.toString()
}

@ExpoModule
private class Timers : Module() {
  private var last: Timer? = null

  @JS fun create(): Timer = Timer().also { last = it }

  @JS fun secondsOf(timer: Timer): Int = timer.seconds

  @JS fun orZero(timer: Timer?): Int = timer?.seconds ?: 0
}

class SharedObjectTest {
  companion object {
    private val intType = AnyType(TypeDescriptor.Int)
    private val boolType = AnyType(TypeDescriptor.Bool)
    private val stringType = AnyType(TypeDescriptor.Simple(String::class.java, false))
    private val counterType = AnyType(TypeDescriptor.Simple(Counter::class.java, false))
    private val tagType = AnyType(TypeDescriptor.Simple(Tag::class.java, false))

    init {
      ExpoHermes.ensureLoaded()

      // The registry is process-wide by design, so a class is described once per JVM rather than
      // once per runtime — which is what lets one instance serve every runtime.
      SharedObjectRegistry.register("Counter", Counter::class.java) {
        function("increment", returns = intType)
        property("value", intType, mutable = true)
      }
      SharedObjectRegistry.register("Tag", Tag::class.java) {
        function("label", returns = stringType)
      }
    }
  }

  private fun HermesRuntime.registerCounters(module: CounterModule = CounterModule()): CounterModule {
    moduleRegistry.register("Counters", module) {
      function("create", returns = counterType)
      function("current", returns = counterType)
      function("bumpTwice", counterType, returns = intType)
    }
    return module
  }

  @Test
  fun `a module can return a shared object and JavaScript can call its members`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()

      assertEquals(
        "1",
        runtime.evaluateAsString("globalThis.c = expo.modules.Counters.create(); c.increment()"),
      )
      assertEquals("2", runtime.evaluateAsString("c.increment()"))
      assertEquals("2", runtime.evaluateAsString("c.value"))

      runtime.evaluate("c.value = 40")
      assertEquals("41", runtime.evaluateAsString("c.increment()"))
    }
  }

  @Test
  fun `the same native object reads as the same JavaScript object`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()

      assertEquals(
        "true",
        runtime.evaluateAsString(
          "globalThis.a = expo.modules.Counters.create(); a === expo.modules.Counters.current()",
        ),
      )
    }
  }

  @Test
  fun `a shared object crosses back into Kotlin as the instance it came from`() {
    HermesRuntime().use { runtime ->
      val module = runtime.registerCounters()

      assertEquals(
        "2",
        runtime.evaluateAsString(
          "globalThis.a = expo.modules.Counters.create(); expo.modules.Counters.bumpTwice(a)",
        ),
      )
      // The same instance on both sides: the count carried over rather than starting again, and
      // Kotlin sees it too.
      assertEquals("3", runtime.evaluateAsString("a.increment()"))
      assertEquals(3, requireNotNull(module.last).getValue())
    }
  }

  @Test
  fun `a shared object of the wrong class is rejected at the boundary`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()
      runtime.moduleRegistry.register("Tags", TagModule()) {
        function("create", returns = tagType)
      }

      val error = runtime.evaluateAsString(
        """
        try {
          expo.modules.Counters.bumpTwice(expo.modules.Tags.create());
          'no error';
        } catch (e) {
          String(e.message);
        }
        """.trimIndent(),
      )
      assertTrue("Expected Counter" in error, "unexpected message: $error")
      assertTrue("got Tag" in error, "unexpected message: $error")
    }
  }

  @Test
  fun `a plain JavaScript object is not accepted where a shared object is declared`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()

      val error = runtime.evaluateAsString(
        "try { expo.modules.Counters.bumpTwice({}); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue("not a shared object" in error, "unexpected message: $error")
    }
  }

  @Test
  fun `release detaches the native object and runs the hook exactly once`() {
    HermesRuntime().use { runtime ->
      val module = runtime.registerCounters()

      runtime.evaluate("globalThis.c = expo.modules.Counters.create(); c.increment();")
      assertEquals(0, requireNotNull(module.last).releaseCount)

      runtime.evaluate("c.release(); c.release();")
      assertEquals(1, requireNotNull(module.last).releaseCount)

      val error = runtime.evaluateAsString(
        "try { c.increment(); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue("released" in error, "unexpected message: $error")
    }
  }

  @Test
  fun `a released object cannot be handed to JavaScript again`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()

      runtime.evaluate("expo.modules.Counters.create().release();")
      val error = runtime.evaluateAsString(
        "try { expo.modules.Counters.current(); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue("released" in error, "unexpected message: $error")
    }
  }

  @Test
  fun `an unknown member reads as undefined rather than throwing`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()

      // Foreign code probes objects it is handed with arbitrary keys — React Native asks for
      // `$$typeof`, for one — so an unknown key stays quiet, as it would on any plain object. It
      // costs nothing either: the read misses the whole prototype chain without entering C++.
      assertEquals(
        "undefined",
        runtime.evaluateAsString("typeof expo.modules.Counters.create().nope"),
      )
    }
  }

  @Test
  fun `a detached method has no receiver, as on a JavaScript class`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()

      // One function per class serves every instance and reads its receiver from `this`, so
      // `const inc = c.increment` loses it — the same thing that happens to a method plucked off an
      // instance of a real class. `bind` is the fix, and it keeps the object alive.
      runtime.evaluate("globalThis.c = expo.modules.Counters.create();")
      val error = runtime.evaluateAsString(
        "(() => { const inc = c.increment; try { inc(); return 'no error'; } catch (e) { return String(e.message); } })()",
      )
      assertTrue("increment" in error, "unexpected message: $error")

      runtime.evaluate("globalThis.inc = c.increment.bind(c);")
      assertEquals("1", runtime.evaluateAsString("inc()"))
      assertEquals("2", runtime.evaluateAsString("inc()"))
    }
  }

  @Test
  fun `enumeration sees the exported properties`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()

      runtime.evaluate("globalThis.c = expo.modules.Counters.create(); c.value = 7;")

      // Nothing lives on the instance: the exports are on the class's prototype, so `Object.keys`
      // — own enumerable properties only — sees nothing, exactly as on an instance of a JavaScript
      // class whose fields are accessors.
      assertEquals("0", runtime.evaluateAsString("Object.keys(c).length"))
      // `in` and `for...in` walk the chain, and the properties are enumerable there while the
      // methods are not, so enumeration reports the object's data.
      assertEquals("true", runtime.evaluateAsString("'increment' in c"))
      assertEquals(
        "value",
        runtime.evaluateAsString("(() => { const k = []; for (const n in c) k.push(n); return k.join(); })()"),
      )
    }
  }

  @Test
  fun `boolean exports still work alongside shared objects`() {
    HermesRuntime().use { runtime ->
      // Guards the descriptor decoder: adding a leaf kind must not disturb the existing ones.
      runtime.moduleRegistry.register("Flags", TagModule()) {
        function("create", returns = tagType)
      }
      assertEquals("tag", runtime.evaluateAsString("expo.modules.Flags.create().label()"))
      assertEquals("boolean", runtime.evaluateAsString("typeof true"))
      assertTrue(boolType.codes.size == 1)
    }
  }

  @Test
  fun `one native object serves two runtimes`() {
    // The whole point of the design: the registry is process-wide and the façade is a host object,
    // so a second runtime reaching the same Kotlin instance shares its state rather than proxying
    // it. Two live runtimes is an established pattern here — see HermesRuntimeTest's
    // `each runtime asks its own registry`.
    val module = CounterModule()
    HermesRuntime().use { first ->
      HermesRuntime().use { second ->
        first.registerCounters(module)
        second.registerCounters(module)

        first.evaluate("globalThis.c = expo.modules.Counters.create(); c.increment();")

        // Reached through the second runtime, the count carries on rather than restarting.
        assertEquals("2", second.evaluateAsString("expo.modules.Counters.current().increment()"))
        assertEquals("3", first.evaluateAsString("c.increment()"))
        assertEquals(3, requireNotNull(module.last).getValue())

        // Identity still holds inside each runtime, independently.
        assertEquals(
          "true",
          second.evaluateAsString(
            "expo.modules.Counters.current() === expo.modules.Counters.current()",
          ),
        )
      }
    }
  }

  @Test
  fun `releasing in one runtime releases in all of them`() {
    val module = CounterModule()
    HermesRuntime().use { first ->
      HermesRuntime().use { second ->
        first.registerCounters(module)
        second.registerCounters(module)

        first.evaluate("globalThis.c = expo.modules.Counters.create();")
        second.evaluate("expo.modules.Counters.current().release();")

        assertEquals(1, requireNotNull(module.last).releaseCount)
        val error = first.evaluateAsString(
          "try { c.increment(); 'no error'; } catch (e) { String(e.message); }",
        )
        assertTrue("released" in error, "unexpected message: $error")
      }
    }
  }

  @Test
  fun `a shared object is callable from a runtime on another thread`() {
    // The chosen semantics: a call runs synchronously on whichever JS thread made it. Nothing here
    // hops threads, and nothing needs to — the argument buffer is thread-local on both sides and
    // kolibri attaches an unattached thread on demand.
    val module = CounterModule()
    HermesRuntime().use { first ->
      first.registerCounters(module)
      first.evaluate("globalThis.c = expo.modules.Counters.create(); c.increment();")

      val results = ArrayBlockingQueue<Result<String>>(1)
      val worker = Thread {
        results.put(
          runCatching {
            // A jsi::Runtime is thread-affine, so this one is created and used here only.
            HermesRuntime().use { second ->
              second.registerCounters(module)
              second.evaluateAsString("expo.modules.Counters.current().increment()")
            }
          },
        )
      }
      worker.start()

      val onOtherThread = requireNotNull(results.poll(30, TimeUnit.SECONDS)) {
        "the second runtime never answered"
      }.getOrThrow()
      worker.join()

      assertEquals("2", onOtherThread)
      assertEquals("3", first.evaluateAsString("c.increment()"))
    }
  }

  @Test
  fun `a facade is a plain object, so it does not cross runtimes by reference`() {
    // A known limitation, pinned down here so it cannot change by accident.
    //
    // `react-native-worklets` carries a value into a worklet runtime by reference only when it is a
    // `jsi::HostObject` — it holds the same `shared_ptr` and calls `Object::createFromHostObject`
    // in the target. A façade is a plain object behind a class prototype, which worklets classifies
    // as neither a host object nor a plain JavaScript object (its prototype is not
    // `Object.prototype`), so it refuses to clone one. Reaching the same native object from a
    // worklet means going through a module registered in that runtime, as every cross-runtime test
    // here does.
    //
    // `TestSupport.transplantHostObject` is exactly the move worklets makes, so asking it to move a
    // façade is how that refusal is observed without an app.
    HermesRuntime().use { first ->
      HermesRuntime().use { second ->
        first.registerCounters()
        first.evaluate("globalThis.c = expo.modules.Counters.create();")

        val error = assertFailsWith<RuntimeException> {
          TestSupport.transplantHostObject(first, second, "globalThis.c", "moved")
        }
        assertTrue(
          "not a host object" in (error.message ?: ""),
          "unexpected message: ${error.message}",
        )
      }
    }
  }

  @Test
  fun `an annotated class needs no hand-written description`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Timers())

      assertEquals("1", runtime.evaluateAsString("globalThis.t = expo.modules.Timers.create(); t.tick()"))
      assertEquals("2", runtime.evaluateAsString("t.tick()"))
      assertEquals("2", runtime.evaluateAsString("t.seconds"))

      runtime.evaluate("t.seconds = 10")
      assertEquals("11", runtime.evaluateAsString("t.tick()"))

      // A converted argument still goes through its trampoline while the receiver stays a reference.
      assertEquals("at 11", runtime.evaluateAsString("t.describe('at ')"))

      // And back into Kotlin, both non-null and nullable.
      assertEquals("11", runtime.evaluateAsString("expo.modules.Timers.secondsOf(t)"))
      assertEquals("11", runtime.evaluateAsString("expo.modules.Timers.orZero(t)"))
      assertEquals("0", runtime.evaluateAsString("expo.modules.Timers.orZero(null)"))
    }
  }

  @Test
  fun `a shared ref carries a native value JavaScript never sees`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Refs())

      assertEquals("5", runtime.evaluateAsString("globalThis.r = expo.modules.Refs.text('hello'); r.length()"))
      // The value itself is not exposed — only what the class chose to export, plus release().
      assertEquals("undefined", runtime.evaluateAsString("typeof r.ref"))
      assertEquals("StringBuilder", runtime.evaluateAsString("r.nativeRefType"))

      assertEquals("hello", runtime.evaluateAsString("expo.modules.Refs.readText(r)"))
      assertEquals("hello", runtime.evaluateAsString("expo.modules.Refs.readAny(r)"))
    }
  }

  @Test
  fun `a shared ref carrying the wrong value is rejected`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Refs())

      runtime.evaluate("globalThis.n = expo.modules.Refs.number();")
      assertEquals("AtomicInteger", runtime.evaluateAsString("n.nativeRefType"))

      // Declared as the concrete subclass: rejected natively, on the class.
      val byClass = runtime.evaluateAsString(
        "try { expo.modules.Refs.readText(n); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue("Expected TextRef" in byClass, "unexpected message: $byClass")

      // Declared as SharedRef<StringBuilder>: every SharedRef is the same erased class at the JNI
      // boundary, so this one is caught in Kotlin instead.
      val byPayload = runtime.evaluateAsString(
        "try { expo.modules.Refs.readAny(n); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue(
        "SharedRef<StringBuilder>" in byPayload && "AtomicInteger" in byPayload,
        "unexpected message: $byPayload",
      )
    }
  }

  @Test
  fun `a method reads as the same function every time`() {
    HermesRuntime().use { runtime ->
      runtime.registerCounters()

      // A method lives on the class's prototype, so repeated reads answer the same object rather
      // than a fresh one — which is both what a JavaScript class does and what makes a method
      // access cheap.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "globalThis.c = expo.modules.Counters.create(); c.increment === c.increment",
        ),
      )
      // Per class, not per object: one function serves every instance and takes its receiver from
      // `this`.
      assertEquals(
        "true",
        runtime.evaluateAsString("c.increment === expo.modules.Counters.create().increment"),
      )
      // And it still calls the right receiver.
      assertEquals("1", runtime.evaluateAsString("c.increment()"))
      assertEquals("2", runtime.evaluateAsString("c.increment()"))
    }
  }

  @Test
  fun `a shared class can be constructed from JavaScript`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Greeters())

      assertEquals(
        "Hi, world!",
        runtime.evaluateAsString(
          "globalThis.g = new expo.modules.Greeters.Greeter('Hi'); g.greet('world')",
        ),
      )
      assertEquals("Hi", runtime.evaluateAsString("g.greeting"))

      // A real class object: `instanceof` holds, and `constructor` points back at it.
      assertEquals("true", runtime.evaluateAsString("g instanceof expo.modules.Greeters.Greeter"))
      assertEquals(
        "true",
        runtime.evaluateAsString("g.constructor === expo.modules.Greeters.Greeter"),
      )

      // Each `new` is its own native object.
      assertEquals(
        "false",
        runtime.evaluateAsString("g === new expo.modules.Greeters.Greeter('Hi')"),
      )

      // And it is an ordinary shared object otherwise.
      runtime.evaluate("g.release()")
      val error = runtime.evaluateAsString(
        "try { g.greet('x'); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue("released" in error, "unexpected message: $error")
    }
  }

  @Test
  fun `an instance handed over by a module shares the constructed class identity`() {
    HermesRuntime().use { runtime ->
      val module = Greeters()
      runtime.moduleRegistry.register(module)

      // A `new`-built object goes into Kotlin and comes back out.
      assertEquals(
        "Hi",
        runtime.evaluateAsString(
          "globalThis.g2 = new expo.modules.Greeters.Greeter('Hi'); " +
            "expo.modules.Greeters.remember(g2)",
        ),
      )
      assertEquals(module.last(), requireNotNull(module.lastOrNull()))

      // The same JavaScript object on the way back, and still of its class — however it was made.
      assertEquals("true", runtime.evaluateAsString("expo.modules.Greeters.last() === g2"))
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "expo.modules.Greeters.last() instanceof expo.modules.Greeters.Greeter",
        ),
      )
    }
  }

  @Test
  fun `a constructor reports a wrong argument list`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Greeters())

      val missing = runtime.evaluateAsString(
        "try { new expo.modules.Greeters.Greeter(); 'no error'; } catch (e) { String(e.message); }",
      )
      assertTrue(
        "Greeter expects 1 argument(s), but received 0" in missing,
        "unexpected message: $missing",
      )
    }
  }

  @Test
  fun `neither prototype nor constructor shows up in enumeration`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Greeters())

      assertEquals(
        "false",
        runtime.evaluateAsString(
          "Object.keys(expo.modules.Greeters.Greeter).includes('prototype')",
        ),
      )
      // `for...in` walks the prototype chain, so an enumerable `constructor` would surface there.
      assertEquals(
        "false",
        runtime.evaluateAsString(
          "const g4 = new expo.modules.Greeters.Greeter('Hi');" +
            "const seen = []; for (const k in g4) seen.push(k); seen.includes('constructor');",
        ),
      )
    }
  }

  @Test
  fun `a nested shared class is exposed on its module without being declared`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Widgets())

      // Nesting is the declaration: `Label` shows up on the module because it lives inside it.
      assertEquals(
        "[hi]",
        runtime.evaluateAsString(
          "globalThis.l = new expo.modules.Widgets.Label('hi'); l.render()",
        ),
      )
      assertEquals("true", runtime.evaluateAsString("l instanceof expo.modules.Widgets.Label"))

      // And it is an ordinary shared object: back into Kotlin as the instance it came from.
      assertEquals("[hi]", runtime.evaluateAsString("expo.modules.Widgets.renderOf(l)"))
    }
  }

  @Test
  fun `a class declared elsewhere is exposed by listing it on the module`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Badges())

      // `Badge` carries @ExpoSharedObject(name = "Renamed"), so that is the name the class object takes — the
      // same rule every other export follows.
      assertEquals(
        "7",
        runtime.evaluateAsString("globalThis.b = new expo.modules.Badges.Renamed(7); b.value()"),
      )
      assertEquals("true", runtime.evaluateAsString("b instanceof expo.modules.Badges.Renamed"))
      assertEquals("undefined", runtime.evaluateAsString("typeof expo.modules.Badges.Badge"))
    }
  }

  @Test
  fun `a constructor argument can ride the binary buffer`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Markers())

      assertEquals(
        "3@1,2:a|b",
        runtime.evaluateAsString(
          "globalThis.m = new expo.modules.Markers.Marker(3, { x: 1, y: 2 }, ['a', 'b']); " +
            "m.describe()",
        ),
      )
      assertEquals("true", runtime.evaluateAsString("m instanceof expo.modules.Markers.Marker"))
      assertEquals("3@1,2:a|b", runtime.evaluateAsString("expo.modules.Markers.describeOf(m)"))
    }
  }

  @Test
  fun `a constructor argument that only needs converting still gets a trampoline`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Windows())

      assertEquals(
        "1500",
        runtime.evaluateAsString(
          "globalThis.w = new expo.modules.Windows.Window(1.5); w.millis()",
        ),
      )
      assertEquals("true", runtime.evaluateAsString("w instanceof expo.modules.Windows.Window"))
    }
  }

  @Test
  fun `a constructor reports a missing argument before touching the buffer`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Markers())

      val error = runtime.evaluateAsString(
        "try { new expo.modules.Markers.Marker(1); 'no error'; } " +
          "catch (e) { String(e.message); }",
      )
      assertTrue(
        "Marker expects 3 argument(s), but received 1" in error,
        "unexpected message: $error",
      )
    }
  }

  @Test
  fun `a nested shared class with no public constructor is not exposed`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Widgets())

      // `Hidden`'s only constructor is private, so there is nothing for `new` to reach and no
      // class object is installed.
      assertEquals("undefined", runtime.evaluateAsString("typeof expo.modules.Widgets.Hidden"))
      // It still works as a shared object when Kotlin hands one over.
      assertEquals("hidden", runtime.evaluateAsString("expo.modules.Widgets.hidden().name()"))
    }
  }
}
