package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
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
 * An `Any` argument through the tagged dynamic buffer codec (`Buffer.YES`) vs its JNI object slot
 * (the default).
 *
 * Each pair differs only in the argument's transport. The result is an `Int` (a register-width
 * JNI slot) on both sides, so only the inbound conversion is measured.
 */
@ExpoModule(name = "DynBench")
class DynamicOps : Module() {
  @JS
  fun anyBuffered(@BufferMode(Buffer.YES) value: Any?): Int = if (value == null) 0 else 1

  @JS
  fun anyDirect(@BufferMode(Buffer.NO) value: Any?): Int = if (value == null) 0 else 1

  @JS
  fun listBuffered(@BufferMode(Buffer.YES) values: List<Any?>): Int = values.size

  @JS
  fun listDirect(@BufferMode(Buffer.NO) values: List<Any?>): Int = values.size

  @JS
  fun mapBuffered(@BufferMode(Buffer.YES) map: Map<String, Any?>): Int = map.size

  @JS
  fun mapDirect(@BufferMode(Buffer.NO) map: Map<String, Any?>): Int = map.size

  @JS
  fun mapsBuffered(@BufferMode(Buffer.YES) values: List<Map<String, Any?>>): Int = values.size

  @JS
  fun mapsDirect(@BufferMode(Buffer.NO) values: List<Map<String, Any?>>): Int = values.size

  /** Correctness probes: the argument rides the buffer, the result comes back in a JNI slot. */
  @JS
  fun echoBuffered(@BufferMode(Buffer.YES) value: Any?): Any? = value

  @JS
  fun echoDirect(@BufferMode(Buffer.NO) value: Any?): Any? = value
}

internal class DynamicCase(val payload: String, val function: String)

internal val DYNAMIC_CASES: Map<String, DynamicCase> = buildMap {
  put("number", DynamicCase("42.5", "any"))
  put("string[16]", DynamicCase("'x'.repeat(16)", "any"))
  for (size in listOf(10, 100, 1_000)) {
    put("doubles[$size]", DynamicCase("Array.from({length: $size}, (_, i) => i * 0.5)", "list"))
  }
  put("strings[100]", DynamicCase("Array.from({length: 100}, (_, i) => 'value-' + i)", "list"))
  put(
    "mixed[100]",
    DynamicCase(
      "Array.from({length: 100}, (_, i) => [i * 0.5, 'v' + i, !!(i % 2), null][i % 4])",
      "list",
    ),
  )
  put("record[4f]", DynamicCase("({id: 7, name: 'seven', x: 1.5, flag: true})", "map"))
  put(
    "record[12f]",
    DynamicCase(
      "({id: 7, name: 'n7', desc: 'a chunky options object', x: 1.5, y: 2.5, z: 3.5, " +
        "count: 42, ratio: 0.75, active: true, tag: 'tag-7', weight: 12.25, rank: 3})",
      "map",
    ),
  )
  put(
    "nested",
    DynamicCase(
      "({user: {id: 1, name: 'Ada', tags: ['a', 'b', 'c']}, " +
        "items: Array.from({length: 10}, (_, i) => ({id: i, price: i * 1.25, labels: ['x', 'y']})), " +
        "meta: {page: 1, total: 10, ok: true}})",
      "map",
    ),
  )
  put(
    "maps[100]",
    DynamicCase(
      "Array.from({length: 100}, (_, i) => ({id: i, name: 'n' + i, x: i * 0.5, flag: !!(i % 2)}))",
      "maps",
    ),
  )
  put(
    "maps[100]/wide",
    DynamicCase(
      "Array.from({length: 100}, (_, i) => ({id: i, name: 'n' + i, desc: 'row description ' + i, " +
        "x: i * 0.5, y: i * 1.5, z: i * 2.5, count: i, ratio: i / 100, active: !!(i % 2), " +
        "tag: 'tag-' + i, weight: i * 0.25, rank: i % 10}))",
      "maps",
    ),
  )
}

internal class DynamicState(ops: Int) {
  private val js = JsLoopRuntime(ops)

  fun setUp(name: String) {
    val case = requireNotNull(DYNAMIC_CASES[name]) { "unknown case: $name" }
    val runtime = js.open()
    runtime.moduleRegistry.register(DynamicOps())
    runtime.evaluate("globalThis.DynBench = expo.modules.DynBench")

    // Both transports must hand Kotlin the same value; a codec mismatch fails setup.
    val check = runtime.evaluate(
      """
      (() => {
        // Both sides decode into a HashMap, so compare with sorted keys.
        const canon = (v) => JSON.stringify(v, (_, x) =>
          x && typeof x === 'object' && !Array.isArray(x)
            ? Object.fromEntries(Object.keys(x).sort().map((k) => [k, x[k]]))
            : x);
        const p = ${case.payload};
        const a = canon(DynBench.echoBuffered(p));
        const b = canon(DynBench.echoDirect(p));
        const c = canon(p);
        return a === c && b === c ? 'ok' : 'buffered=' + a + ' direct=' + b + ' expected=' + c;
      })()
      """.trimIndent(),
    )
    check(check.getString() == "ok") { "case $name: ${check.getString()}" }

    js.install("__buffered", "const p = ${case.payload}", "DynBench.${case.function}Buffered(p)")
    js.install("__direct", "const p = ${case.payload}", "DynBench.${case.function}Direct(p)")
  }

  fun buffered(): JavaScriptValue = js.run("__buffered")

  fun direct(): JavaScriptValue = js.run("__direct")

  fun close() = js.close()
}

private const val OPS_FAST = 50_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DynamicTransportFastBenchmark {
  @Param("number", "string[16]", "doubles[10]", "record[4f]", "record[12f]")
  var case: String = ""

  private val state = DynamicState(OPS_FAST)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_FAST)
  fun buffered(): JavaScriptValue = state.buffered()

  @Benchmark
  @OperationsPerInvocation(OPS_FAST)
  fun direct(): JavaScriptValue = state.direct()
}

private const val OPS_MID = 5_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DynamicTransportMidBenchmark {
  @Param("doubles[100]", "strings[100]", "mixed[100]", "nested")
  var case: String = ""

  private val state = DynamicState(OPS_MID)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_MID)
  fun buffered(): JavaScriptValue = state.buffered()

  @Benchmark
  @OperationsPerInvocation(OPS_MID)
  fun direct(): JavaScriptValue = state.direct()
}

private const val OPS_SLOW = 500

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DynamicTransportSlowBenchmark {
  @Param("doubles[1000]", "maps[100]", "maps[100]/wide")
  var case: String = ""

  private val state = DynamicState(OPS_SLOW)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_SLOW)
  fun buffered(): JavaScriptValue = state.buffered()

  @Benchmark
  @OperationsPerInvocation(OPS_SLOW)
  fun direct(): JavaScriptValue = state.direct()
}

/**
 * An `Any` result through the tagged dynamic buffer codec (`returns = Buffer.YES`) vs its JNI
 * object slot (the default). Both return the same Kotlin value, which [load] decodes once from the
 * case's JS payload.
 */
@ExpoModule(name = "DynResultBench")
class DynamicResultOps : Module() {
  private var value: Any? = null

  @JS
  fun load(@BufferMode(Buffer.NO) value: Any?) {
    this.value = value
  }

  @JS
  @BufferMode(returns = Buffer.YES)
  fun buffered(): Any? = value

  @JS
  @BufferMode(returns = Buffer.NO)
  fun direct(): Any? = value
}

internal class DynamicResultState(ops: Int) {
  private val js = JsLoopRuntime(ops)

  fun setUp(name: String) {
    val case = requireNotNull(DYNAMIC_CASES[name]) { "unknown case: $name" }
    val runtime = js.open()
    runtime.moduleRegistry.register(DynamicResultOps())
    runtime.evaluate("globalThis.DynResultBench = expo.modules.DynResultBench")

    // Both transports must hand JS the same value; a codec mismatch fails setup.
    val check = runtime.evaluate(
      """
      (() => {
        const canon = (v) => JSON.stringify(v, (_, x) =>
          x && typeof x === 'object' && !Array.isArray(x)
            ? Object.fromEntries(Object.keys(x).sort().map((k) => [k, x[k]]))
            : x);
        const p = ${case.payload};
        DynResultBench.load(p);
        const a = canon(DynResultBench.buffered());
        const b = canon(DynResultBench.direct());
        const c = canon(p);
        return a === c && b === c ? 'ok' : 'buffered=' + a + ' direct=' + b + ' expected=' + c;
      })()
      """.trimIndent(),
    )
    check(check.getString() == "ok") { "case $name: ${check.getString()}" }

    // The loop sums numbers, so map each result to one.
    js.install("__buffered", "", "(DynResultBench.buffered() != null ? 1 : 0)")
    js.install("__direct", "", "(DynResultBench.direct() != null ? 1 : 0)")
  }

  fun buffered(): JavaScriptValue = js.run("__buffered")

  fun direct(): JavaScriptValue = js.run("__direct")

  fun close() = js.close()
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DynamicResultFastBenchmark {
  @Param("number", "string[16]", "doubles[10]", "record[12f]")
  var case: String = ""

  private val state = DynamicResultState(OPS_FAST)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_FAST)
  fun buffered(): JavaScriptValue = state.buffered()

  @Benchmark
  @OperationsPerInvocation(OPS_FAST)
  fun direct(): JavaScriptValue = state.direct()
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DynamicResultMidBenchmark {
  @Param("doubles[100]", "strings[100]", "nested")
  var case: String = ""

  private val state = DynamicResultState(OPS_MID)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_MID)
  fun buffered(): JavaScriptValue = state.buffered()

  @Benchmark
  @OperationsPerInvocation(OPS_MID)
  fun direct(): JavaScriptValue = state.direct()
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DynamicResultSlowBenchmark {
  @Param("maps[100]")
  var case: String = ""

  private val state = DynamicResultState(OPS_SLOW)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_SLOW)
  fun buffered(): JavaScriptValue = state.buffered()

  @Benchmark
  @OperationsPerInvocation(OPS_SLOW)
  fun direct(): JavaScriptValue = state.direct()
}
