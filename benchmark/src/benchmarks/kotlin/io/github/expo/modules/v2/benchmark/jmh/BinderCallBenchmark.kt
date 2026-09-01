package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.annotations.Buffer
import io.github.expo.modules.v2.annotations.JS
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.modules.Module
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
 * FunctionBinder dispatch: what a host call costs end to end, per declaration shape.
 *
 * The direct/buffered pairs price a `buffered()` declaration on a leaf — the same value through a
 * JNI slot and through the trampoline payload. `buffer-list[0]` and `buffer-list[1]` are the
 * payload floor: an empty and a one-element list.
 */
private const val OPS = 100_000

/**
 * Each direct/buffered pair is two Kotlin functions exported under the names the cases expect: one
 * pinned to a JNI slot with `Buffer.NO`, one left on the default, which buffers. A single Kotlin
 * function has exactly one transport, so the pair has to be two.
 */
@JS(name = "FunctionBinderBench")
private class BinderOps : Module() {
  @JS
  fun intValue(): Int = 42

  @JS(name = "stringValue", buffer = Buffer.NO)
  fun stringValueDirect(): String = "forty-two"

  @JS(name = "stringValueBuffered")
  fun stringValueBuffered(): String = "forty-two"

  @JS(name = "echoString", buffer = Buffer.NO)
  fun echoStringDirect(value: String): String = value

  @JS(name = "echoStringBuffered")
  fun echoStringBuffered(value: String): String = value

  @JS
  fun sum(values: List<Double>): Double = values.sum()
}

/** The JS prelude that prepares the call, and the call itself. */
private class BinderCase(val prelude: String, val call: String)

private val BINDER_CASES = mapOf(
  "direct-int" to BinderCase("const f = BinderBench.intValue", "f()"),
  "direct-string" to BinderCase("const f = BinderBench.stringValue", "f().length"),
  "buffered-string-ret" to BinderCase("const f = BinderBench.stringValueBuffered", "f().length"),
  "direct-string-echo" to
    BinderCase("const f = BinderBench.echoString; const s = 'forty-two'", "f(s).length"),
  "buffered-string-echo" to
    BinderCase("const f = BinderBench.echoStringBuffered; const s = 'forty-two'", "f(s).length"),
  "buffer-list[0]" to BinderCase("const f = BinderBench.sum; const p = []", "f(p)"),
  "buffer-list[1]" to BinderCase("const f = BinderBench.sum; const p = [42]", "f(p)"),
)

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class BinderCallBenchmark {
  @Param(
    "direct-int",
    "direct-string",
    "buffered-string-ret",
    "direct-string-echo",
    "buffered-string-echo",
    "buffer-list[0]",
    "buffer-list[1]",
  )
  var case: String = ""

  private val js = JsLoopRuntime(OPS)

  @Setup
  fun setUp() {
    val binderCase = requireNotNull(BINDER_CASES[case]) { "unknown case: $case" }
    val runtime = js.open()
    runtime.moduleRegistry.register(BinderOps())
    runtime.evaluate("globalThis.BinderBench = expo.modules.FunctionBinderBench")
    js.install("__binder", binderCase.prelude, binderCase.call)
  }

  @TearDown
  fun tearDown() = js.close()

  @Benchmark
  @OperationsPerInvocation(OPS)
  fun call(): JavaScriptValue = js.run("__binder")
}
