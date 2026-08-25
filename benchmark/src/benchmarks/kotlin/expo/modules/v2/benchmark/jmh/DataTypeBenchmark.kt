package expo.modules.v2.benchmark.jmh

import expo.modules.v2.benchmark.datatype.installDataTypeBatch
import expo.modules.v2.benchmark.datatype.registerDataTypeBenchModule
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
 * Focused full-round-trip datatype costs. Payloads are allocated once in JavaScript setup; each
 * measured operation is one module call that decodes the argument and encodes the return value.
 */
private class DataTypeState(private val operations: Int) {
  private val js = JsLoopRuntime(operations)

  fun setUp(caseName: String) {
    val runtime = js.open()
    registerDataTypeBenchModule(runtime)
    installDataTypeBatch(runtime, "__datatype", caseName, operations)
  }

  fun run(): JavaScriptValue = js.run("__datatype")

  fun close() = js.close()
}

private const val PRIMITIVE_OPS = 100_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DataTypePrimitiveBenchmark {
  @Param("primitive/boolean", "primitive/int", "primitive/long", "primitive/double")
  var case: String = ""

  private val state = DataTypeState(PRIMITIVE_OPS)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(PRIMITIVE_OPS)
  fun roundTrip(): JavaScriptValue = state.run()
}

private const val STRING_OPS = 10_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DataTypeStringBenchmark {
  @Param("string/ascii[16]", "string/ascii[1024]", "string/utf16[1024]")
  var case: String = ""

  private val state = DataTypeState(STRING_OPS)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(STRING_OPS)
  fun roundTrip(): JavaScriptValue = state.run()
}

private const val LIST_OPS = 5_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DataTypeListBenchmark {
  @Param("list/double[16]", "list/double[256]", "list/string[64]")
  var case: String = ""

  private val state = DataTypeState(LIST_OPS)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(LIST_OPS)
  fun roundTrip(): JavaScriptValue = state.run()
}

private const val MAP_OPS = 1_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DataTypeMapBenchmark {
  @Param("map/double[16]", "map/double[128]", "map/string[32]")
  var case: String = ""

  private val state = DataTypeState(MAP_OPS)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(MAP_OPS)
  fun roundTrip(): JavaScriptValue = state.run()
}

private const val RECORD_OPS = 5_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class DataTypeRecordBenchmark {
  @Param("record/2-fields", "record/4-fields", "record/12-fields")
  var case: String = ""

  private val state = DataTypeState(RECORD_OPS)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(RECORD_OPS)
  fun roundTrip(): JavaScriptValue = state.run()
}
