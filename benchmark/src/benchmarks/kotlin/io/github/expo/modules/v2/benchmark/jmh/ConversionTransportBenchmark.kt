package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.JS
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
 * Binary payload vs element-wise / direct JNI, on identical data.
 *
 * Every case is measured twice — `binary` rides the trampoline payload, `direct` crosses as JNI
 * slots — so the two rows of one `(case)` value are the comparison. The array rows are the
 * exception: their direct side is a bulk JNI region copy, not an element-wise walk.
 *
 * The classes differ only in `@OperationsPerInvocation`: a JS loop of that many host calls is one
 * JMH invocation, and the count is sized per tier so an invocation stays long enough to swamp the
 * per-invocation `evaluate` cost (see `EvaluateOverheadBenchmark`) and short enough that JMH gets
 * several invocations per iteration. Tiers follow the iteration counts the `main()` harness used.
 */
internal class TransportCase(val payload: String, val binary: String, val direct: String)

private val doublesPayload = { size: Int -> "Array.from({length: $size}, (_, i) => i * 0.5)" }

internal val TRANSPORT_CASES: Map<String, TransportCase> = buildMap {
  // Typed List<Double>: buffered payload vs a JList slot with boxed elements.
  for (size in listOf(10, 100, 1_000, 10_000)) {
    put(
      "doubles[$size]/list",
      TransportCase(
        doublesPayload(size),
        "Bench.echoTyped(p).length",
        "Bench.echoTypedDirect(p).length",
      ),
    )
  }
  // DoubleArray: buffered payload vs bulk JNI region copies (the direct side is NOT element-wise
  // here — it is the bulk-copy fast path).
  for (size in listOf(10, 100, 1_000, 10_000)) {
    put(
      "doubles[$size]/array",
      TransportCase(
        doublesPayload(size),
        "Bench.echoArrayBuffered(p).length",
        "Bench.echoArray(p).length",
      ),
    )
  }
  // Single String across sizes: buffered payload vs a direct jstring slot.
  for (size in listOf(8, 64, 512, 4_096, 32_768)) {
    put(
      "string[$size]",
      TransportCase(
        "'x'.repeat($size)",
        "Bench.echoStringBuffered(p).length",
        "Bench.echoString(p).length",
      ),
    )
  }
  for (size in listOf(64, 4_096)) {
    put(
      "string[$size]/utf16",
      TransportCase(
        "'ż'.repeat($size)",
        "Bench.echoStringBuffered(p).length",
        "Bench.echoString(p).length",
      ),
    )
  }
  // Boxed nullable Int: payload vs a JInteger slot.
  put("boxed-int", TransportCase("42", "Bench.echoBoxedBuffered(p)", "Bench.echoBoxed(p)"))
  // List<String>: buffered payload vs the dynamic List<Any?> object slot.
  put(
    "strings[100]/list",
    TransportCase(
      "Array.from({length: 100}, (_, i) => 'value-' + i)",
      "Bench.echoStrings(p).length",
      "Bench.echo(p).length",
    ),
  )
  put(
    "strings[100]/list-utf16",
    TransportCase(
      "Array.from({length: 100}, (_, i) => 'wartość-żółć-' + i)",
      "Bench.echoStrings(p).length",
      "Bench.echo(p).length",
    ),
  )
  // Record width sweep: one object as a typed positional payload vs a dynamic Map slot.
  put(
    "record[2f]",
    TransportCase("({id: 7, x: 1.5})", "Bench.echoSmall(p).id", "Bench.echoMap(p).id"),
  )
  put(
    "record[4f]",
    TransportCase(
      "({id: 7, name: 'seven', x: 1.5, flag: true})",
      "Bench.echoOne(p).id",
      "Bench.echoMap(p).id",
    ),
  )
  put(
    "record[12f]",
    TransportCase(
      "({id: 7, name: 'n7', desc: 'a chunky options object', x: 1.5, y: 2.5, z: 3.5, " +
        "count: 42, ratio: 0.75, active: true, tag: 'tag-7', weight: 12.25, rank: 3})",
      "Bench.echoWide(p).id",
      "Bench.echoMap(p).id",
    ),
  )
  // The same 100 objects as typed records (positional payload) vs untyped maps (slots).
  put(
    "records[100]",
    TransportCase(
      "Array.from({length: 100}, (_, i) => ({id: i, name: 'n' + i, x: i * 0.5, flag: !!(i % 2)}))",
      "Bench.echoRecords(p).length",
      "Bench.echoMaps(p).length",
    ),
  )
  put(
    "records[100]/wide",
    TransportCase(
      "Array.from({length: 100}, (_, i) => ({id: i, name: 'n' + i, desc: 'row description ' + i, " +
        "x: i * 0.5, y: i * 1.5, z: i * 2.5, count: i, ratio: i / 100, active: !!(i % 2), " +
        "tag: 'tag-' + i, weight: i * 0.25, rank: i % 10}))",
      "Bench.echoRecordsWide(p).length",
      "Bench.echoMaps(p).length",
    ),
  )
}

/** Both loops of one case, over one runtime. */
internal class TransportState(ops: Int) {
  private val js = JsLoopRuntime(ops)

  fun setUp(name: String) {
    val case = requireNotNull(TRANSPORT_CASES[name]) { "unknown case: $name" }
    registerConversionModule(js.open())
    js.install("__binary", "const p = ${case.payload}", case.binary)
    js.install("__direct", "const p = ${case.payload}", case.direct)
  }

  fun binary(): JavaScriptValue = js.run("__binary")

  fun direct(): JavaScriptValue = js.run("__direct")

  fun close() = js.close()
}

private const val OPS_FAST = 50_000

/** Sub-microsecond payloads: scalars, short strings, narrow records. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class ConversionTransportFastBenchmark {
  @Param(
    "doubles[10]/array",
    "string[8]",
    "string[64]",
    "string[512]",
    "string[64]/utf16",
    "boxed-int",
    "record[2f]",
    "record[4f]",
  )
  var case: String = ""

  private val state = TransportState(OPS_FAST)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_FAST)
  fun binary(): JavaScriptValue = state.binary()

  @Benchmark
  @OperationsPerInvocation(OPS_FAST)
  fun direct(): JavaScriptValue = state.direct()
}

private const val OPS_MID = 5_000

/** Microsecond payloads: 10-100 element lists, kilobyte strings, wide records. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class ConversionTransportMidBenchmark {
  @Param(
    "doubles[10]/list",
    "doubles[100]/list",
    "doubles[100]/array",
    "string[4096]",
    "string[32768]",
    "string[4096]/utf16",
    "strings[100]/list",
    "strings[100]/list-utf16",
    "record[12f]",
    "records[100]",
  )
  var case: String = ""

  private val state = TransportState(OPS_MID)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_MID)
  fun binary(): JavaScriptValue = state.binary()

  @Benchmark
  @OperationsPerInvocation(OPS_MID)
  fun direct(): JavaScriptValue = state.direct()
}

private const val OPS_SLOW = 500

/** The heavy tail: 1 000-10 000 element payloads, one of which overflows the shared buffer. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class ConversionTransportSlowBenchmark {
  @Param(
    "doubles[1000]/list",
    "doubles[10000]/list",
    "doubles[1000]/array",
    "doubles[10000]/array",
    "records[100]/wide",
  )
  var case: String = ""

  private val state = TransportState(OPS_SLOW)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_SLOW)
  fun binary(): JavaScriptValue = state.binary()

  @Benchmark
  @OperationsPerInvocation(OPS_SLOW)
  fun direct(): JavaScriptValue = state.direct()
}
