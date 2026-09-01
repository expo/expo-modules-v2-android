package io.github.expo.modules.v2.benchmark.jmh

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
 * Data classes vs untyped maps, over identical JS payloads: records cross positionally through the
 * buffer, maps carry key strings through JNI object slots.
 *
 * Each case runs as two rows — `record` and `map` — and the directions covered are full echo
 * (argument + return), argument-only (JS -> Kotlin, primitive back), return-only (Kotlin-built
 * values out) and nested records. Tiering works as in [ConversionTransportFastBenchmark].
 */
private class PairedCase(val payload: String, val recordCall: String, val mapCall: String)

private fun itemsPayload(count: Int) =
  "Array.from({length: $count}, (_, i) => ({id: i, name: 'n' + i, x: i * 0.5, flag: !!(i % 2)}))"

private val PAIRED_CASES = mapOf(
  "single-echo" to PairedCase(
    "({id: 7, name: 'seven', x: 3.5, flag: true})",
    "Rec.echoOne(p).id",
    "Rec.echoOneMap(p).id",
  ),
  "single-echo/flagged" to PairedCase(
    "({id: 7, name: 'seven', meta: {x: 3.5, flag: true}})",
    "Rec.echoOneFlagged(p).id",
    "Rec.echoOneMap(p).id",
  ),
  "echo[10]" to PairedCase(itemsPayload(10), "Rec.echo(p).length", "Rec.echoMaps(p).length"),
  "echo[100]" to PairedCase(itemsPayload(100), "Rec.echo(p).length", "Rec.echoMaps(p).length"),
  "echo[1000]" to PairedCase(itemsPayload(1000), "Rec.echo(p).length", "Rec.echoMaps(p).length"),
  "arg-only/sumX[100]" to PairedCase(itemsPayload(100), "Rec.sumX(p)", "Rec.sumXMaps(p)"),
  "return-only/make[100]" to PairedCase("100", "Rec.make(p).length", "Rec.makeMaps(p).length"),
  "nested/orders[50]" to PairedCase(
    "Array.from({length: 50}, (_, i) => " +
      "({item: {id: i, name: 'n' + i, x: i * 0.5, flag: true}, tags: ['new', 'sale', 'hot']}))",
    "Rec.echoOrders(p).length",
    "Rec.echoMaps(p).length",
  ),
)

/** Both loops of one case, over one runtime. */
private class PairedState(ops: Int) {
  private val js = JsLoopRuntime(ops)

  fun setUp(name: String) {
    val case = requireNotNull(PAIRED_CASES[name]) { "unknown case: $name" }
    registerRecordModule(js.open())
    js.install("__record", "const p = ${case.payload}", case.recordCall)
    js.install("__map", "const p = ${case.payload}", case.mapCall)
  }

  fun record(): JavaScriptValue = js.run("__record")

  fun map(): JavaScriptValue = js.run("__map")

  fun close() = js.close()
}

private const val OPS_FAST = 50_000

/** Single-record crossings. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class RecordPairFastBenchmark {
  @Param("single-echo", "single-echo/flagged")
  var case: String = ""

  private val state = PairedState(OPS_FAST)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_FAST)
  fun record(): JavaScriptValue = state.record()

  @Benchmark
  @OperationsPerInvocation(OPS_FAST)
  fun map(): JavaScriptValue = state.map()
}

private const val OPS_MID = 5_000

/** 10-100 record collections, plus the argument-only, return-only and nested directions. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class RecordPairMidBenchmark {
  @Param("echo[10]", "echo[100]", "arg-only/sumX[100]", "return-only/make[100]", "nested/orders[50]")
  var case: String = ""

  private val state = PairedState(OPS_MID)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_MID)
  fun record(): JavaScriptValue = state.record()

  @Benchmark
  @OperationsPerInvocation(OPS_MID)
  fun map(): JavaScriptValue = state.map()
}

private const val OPS_SLOW = 500

/** The 1 000-record page. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class RecordPairSlowBenchmark {
  @Param("echo[1000]")
  var case: String = ""

  private val state = PairedState(OPS_SLOW)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_SLOW)
  fun record(): JavaScriptValue = state.record()

  @Benchmark
  @OperationsPerInvocation(OPS_SLOW)
  fun map(): JavaScriptValue = state.map()
}
