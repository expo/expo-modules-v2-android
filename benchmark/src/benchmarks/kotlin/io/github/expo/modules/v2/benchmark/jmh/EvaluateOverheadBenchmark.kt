package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.jsi.JavaScriptValue
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

/**
 * The floor of the JS-driven harness: what one invocation costs before any host call happens.
 *
 * Every JS-driven benchmark runs `evaluate("__name()")` once per invocation, so this score is the
 * fixed cost each of those invocations carries. Divide it by a class's
 * `@OperationsPerInvocation` to see what it contributes to that class's ns/op — keep it under ~1%
 * when choosing the operation count for a new benchmark.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class EvaluateOverheadBenchmark {
  private val js = JsLoopRuntime(ops = 0)

  @Setup
  fun setUp() {
    js.open()
    // ops = 0, so the installed loop body never runs: what is left is the per-invocation
    // compile-and-call of "__empty()".
    js.install("__empty", "const p = 0", "p")
  }

  @TearDown
  fun tearDown() = js.close()

  @Benchmark
  fun evaluateEmptyLoop(): JavaScriptValue = js.run("__empty")
}
