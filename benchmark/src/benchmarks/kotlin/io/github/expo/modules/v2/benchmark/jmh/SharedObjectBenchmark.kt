package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.annotations.JS
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.modules.Module
import io.github.expo.modules.v2.sharedobjects.SharedObject
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
// kotlinx.benchmark's JVM annotations are typealiases for the JMH ones, so JMH-only annotations
// like this can be used alongside them.
import org.openjdk.jmh.annotations.OperationsPerInvocation

/**
 * Sized off the cheapest case, not the average. `unknown-key` never enters the JVM at ~22 ns/op, so
 * it takes this many to keep `EvaluateOverheadBenchmark`'s ~54 µs per `evaluate` under the ~1% of
 * an invocation [JsLoopRuntime] asks for. The dearest case is ~50x that and simply runs longer.
 */
private const val OPS = 150_000

@JS
private class BenchCounter : SharedObject() {
  private var count: Int = 0

  @JS
  fun tick(): Int = ++count

  @JS
  var seconds: Int = 0
}

@JS(name = "SharedObjectBench")
private class SharedObjectOps : Module() {
  private val shared = BenchCounter()

  /** The steady state: handing JavaScript an object it has already seen. */
  @JS
  fun current(): BenchCounter = shared

  /** The first crossing: an id is assigned and a state and façade are built. */
  @JS
  fun create(): BenchCounter = BenchCounter()

  /** Inbound: the façade is turned back into the Kotlin instance. */
  @JS
  fun tickOf(counter: BenchCounter): Int = counter.tick()

  /** The same work with no shared object involved, as the floor. */
  @JS
  fun tick(): Int = shared.tick()
}

/** The JS prelude that prepares the call, and the call itself. */
private class SharedObjectCase(val prelude: String, val call: String)

private val SHARED_OBJECT_CASES = mapOf(
  // The floor: an ordinary module function, no shared object in sight.
  "module-method" to SharedObjectCase("const m = SharedObjectBench.tick", "m()"),
  // The same call through a façade, which mints a jsi::Function on every property read.
  "method" to SharedObjectCase("const o = SharedObjectBench.current()", "o.tick()"),
  // Bound once, called many times. A method lives on the prototype, so a bare `const f = o.tick`
  // has no receiver — `bind` is what a JavaScript author reaches for, and this is what it costs.
  "method-bound" to SharedObjectCase(
    "const o = SharedObjectBench.current(); const f = o.tick.bind(o)",
    "f()",
  ),
  // A property read reaches the same binder through a prototype accessor.
  "property-read" to SharedObjectCase("const o = SharedObjectBench.current()", "o.seconds"),
  "property-write" to SharedObjectCase("const o = SharedObjectBench.current()", "(o.seconds = i)"),
  // An unknown key misses the whole prototype chain and never enters C++, as on a plain object.
  "unknown-key" to SharedObjectCase("const o = SharedObjectBench.current()", "(o.nope, 1)"),
  // Inbound: native-state lookup, released check, IsInstanceOf, then a fresh local reference.
  "pass-arg" to
    SharedObjectCase("const g = SharedObjectBench.tickOf; const o = SharedObjectBench.current()", "g(o)"),
  // Outbound, already known: one JNI attach call, then the runtime's façade cache answers.
  "return-existing" to
    SharedObjectCase("const c = SharedObjectBench.current", "(c(), 1)"),
  // Outbound, first time: id assignment, a new state, a new façade, a cache store.
  "return-new" to SharedObjectCase("const n = SharedObjectBench.create", "(n(), 1)"),
)

/**
 * What a shared object costs, against the module call that is its closest relative.
 *
 * A module object is a plain `jsi::Object` carrying real function properties and accessors, built
 * once. A shared object's façade is also a plain object, but its members live on one prototype per
 * class per runtime and recover the receiver from `this`, so a method read is an ordinary prototype
 * lookup and the call pays a `getNativeState` the module's does not. `module-method` and `method`
 * price exactly that difference.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class SharedObjectBenchmark {
  @Param(
    "module-method",
    "method",
    "method-bound",
    "property-read",
    "property-write",
    "unknown-key",
    "pass-arg",
    "return-existing",
    "return-new",
  )
  var case: String = ""

  private val js = JsLoopRuntime(OPS)

  @Setup
  fun setUp() {
    val sharedCase = requireNotNull(SHARED_OBJECT_CASES[case]) { "unknown case: $case" }
    val runtime = js.open()
    runtime.moduleRegistry.register(SharedObjectOps())
    runtime.evaluate("globalThis.SharedObjectBench = expo.modules.SharedObjectBench")
    js.install("__sharedObject", sharedCase.prelude, sharedCase.call)
  }

  @TearDown
  fun tearDown() = js.close()

  @Benchmark
  @OperationsPerInvocation(OPS)
  fun call(): JavaScriptValue = js.run("__sharedObject")
}
