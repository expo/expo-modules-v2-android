package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.async.Promise
import io.github.expo.modules.v2.jsi.JavaScriptValue
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
import org.openjdk.jmh.annotations.OperationsPerInvocation

/**
 * What an async export costs per call when its result already exists, by how it produces it.
 *
 * Every async case hands JavaScript a promise that is born settled, so nothing is left on the job
 * queue between invocations. `promise-int` resolves with values past the `Integer` cache, so it
 * pays for a box; `promise-int-cached` resolves with one inside it, so the difference between the
 * two is the box itself.
 */
private const val OPS = 10_000

@ExpoModule(name = "PromiseBench")
private class PromiseOps : Module() {
  @JS
  fun syncInt(value: Int): Int = value * 1000

  @JS
  fun promiseInt(value: Int): Promise<Int> = Promise.resolve(value * 1000)

  @JS
  fun promiseIntCached(value: Int): Promise<Int> = Promise.resolve(value and 63)

  @JS
  fun promiseString(value: Int): Promise<String> = Promise.resolve("forty-two")

  @JS
  fun executorInt(value: Int): Promise<Int> = Promise { resolve, _ -> resolve(value * 1000) }

  @JS
  suspend fun suspendInt(value: Int): Int = value * 1000
}

private class PromiseCase(val function: String, val call: String)

private val PROMISE_CASES = mapOf(
  "sync-int" to PromiseCase("syncInt", "f(i)"),
  "promise-int" to PromiseCase("promiseInt", "(f(i), 1)"),
  "promise-int-cached" to PromiseCase("promiseIntCached", "(f(i), 1)"),
  "promise-string" to PromiseCase("promiseString", "(f(i), 1)"),
  "executor-int" to PromiseCase("executorInt", "(f(i), 1)"),
  "suspend-int" to PromiseCase("suspendInt", "(f(i), 1)"),
)

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class PromiseBenchmark {
  @Param(
    "sync-int",
    "promise-int",
    "promise-int-cached",
    "promise-string",
    "executor-int",
    "suspend-int",
  )
  var case: String = ""

  private val js = JsLoopRuntime(OPS)

  @Setup
  fun setUp() {
    val promiseCase = requireNotNull(PROMISE_CASES[case]) { "unknown case: $case" }
    val runtime = js.open()
    runtime.moduleRegistry.register(PromiseOps())
    js.install(
      "__promise",
      "const f = expo.modules.PromiseBench.${promiseCase.function}",
      promiseCase.call,
    )
  }

  @TearDown
  fun tearDown() = js.close()

  @Benchmark
  @OperationsPerInvocation(OPS)
  fun call(): JavaScriptValue = js.run("__promise")
}
