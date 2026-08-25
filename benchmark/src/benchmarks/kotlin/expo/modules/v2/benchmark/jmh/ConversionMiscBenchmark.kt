package expo.modules.v2.benchmark.jmh

import expo.modules.v2.jsi.JavaScriptValue
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
 * Shapes with only one sensible transport — kept for regression tracking, no binary/direct pair.
 */
private const val OPS_MISC = 5_000

private class MiscCase(val payload: String, val call: String)

private val MISC_CASES = mapOf(
  "doubles[100]/sumTyped" to
    MiscCase("Array.from({length: 100}, (_, i) => i * 0.5)", "Bench.sumTyped(p)"),
  "doubles[100]/echo-dynamic" to
    MiscCase("Array.from({length: 100}, (_, i) => i * 0.5)", "Bench.echo(p).length"),
  "string[16k]/echoStrings-ascii" to
    MiscCase("['x'.repeat(16384)]", "Bench.echoStrings(p).length"),
  "string[16k]/echoStrings-utf16" to
    MiscCase("['ż'.repeat(16384)]", "Bench.echoStrings(p).length"),
  "map[100]/echoMap" to
    MiscCase(
      "Object.fromEntries(Array.from({length: 100}, (_, i) => ['key' + i, i * 1.5]))",
      "Object.keys(Bench.echoMap(p)).length",
    ),
  "nested/echo" to
    MiscCase(
      "Array.from({length: 20}, (_, i) => " +
        "({id: i, name: 'item' + i, tags: ['a', 'b'], meta: {x: i, y: i * 2}}))",
      "Bench.echo(p).length",
    ),
)

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class ConversionMiscBenchmark {
  @Param(
    "doubles[100]/sumTyped",
    "doubles[100]/echo-dynamic",
    "string[16k]/echoStrings-ascii",
    "string[16k]/echoStrings-utf16",
    "map[100]/echoMap",
    "nested/echo",
  )
  var case: String = ""

  private val js = JsLoopRuntime(OPS_MISC)

  @Setup
  fun setUp() {
    val miscCase = requireNotNull(MISC_CASES[case]) { "unknown case: $case" }
    registerConversionModule(js.open())
    js.install("__misc", "const p = ${miscCase.payload}", miscCase.call)
  }

  @TearDown
  fun tearDown() = js.close()

  @Benchmark
  @OperationsPerInvocation(OPS_MISC)
  fun call(): JavaScriptValue = js.run("__misc")
}
