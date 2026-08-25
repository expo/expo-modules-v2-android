package expo.modules.v2.benchmark.jmh

import expo.modules.v2.core.ExpoModulesV2
import expo.modules.v2.records.RecordCodec
import expo.modules.v2.records.Record
import expo.modules.v2.records.RecordField
import expo.modules.v2.records.readers.RecordReader
import expo.modules.v2.records.RecordRegistry
import expo.modules.v2.records.RecordSchema
import expo.modules.v2.records.writers.RecordWriter
import expo.modules.v2.types.AnyType
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import org.openjdk.jmh.annotations.OperationsPerInvocation
import expo.modules.v2.types.TypeDescriptor

/**
 * The Kotlin half of record schema registration: what `RecordRegistry.fetchSchema` costs to build
 * one [RecordSchemaData] — the field-name array, the flat type-code array, the field-optionality
 * array, and the holder.
 *
 * **This is not the whole registration cost.** Native code pulls a schema from inside
 * `RecordRegistry::get`, so the up-call, the five field reads and the C++ parse all run on a path
 * this benchmark cannot reach: `get` caches on first miss, and there is no eviction hook to make it
 * repeat. Treat these numbers as a regression guard on the marshalling, not as the per-schema total.
 *
 * For scale, the earlier callback shape — where Kotlin called back down into `nativeDefineSchema`
 * and the whole crossing was reachable from here — measured 973 / 2695 / 8466 ns at 2 / 11 / 42
 * fields, against 197 / 565 / 1475 ns for the batch push it replaced.
 *
 * Field counts are the real distribution across expo's 345 records: 2 is the median, 11 the p95,
 * 42 the widest (`TextFieldColorsRecord`). Batch sizes bracket it: one schema, a small module, and
 * 96 — the count in expo-ui, the largest single module.
 */
private val FIELD_TYPES = listOf(
  TypeDescriptor.Int,
  TypeDescriptor.Simple(String::class.java, false),
  TypeDescriptor.Double,
  TypeDescriptor.Simple(String::class.java, true),
)

// Distinct classes because the registry keys interned types by record class; one per field count.
private class BenchRecord2 : Record

private class BenchRecord11 : Record

private class BenchRecord42 : Record

/**
 * A registration-only codec: the benchmark never encodes a value, so [encode] and [decode] are
 * unreachable. Everything registration touches — the schema, the field types, the record class —
 * is real.
 */
private class BenchCodec<T : Record>(
  override val recordClass: Class<T>,
  fieldCount: Int,
) : RecordCodec<T> {
  override val schema = RecordSchema(
    recordClass.simpleName,
    true,
    *Array(fieldCount) { RecordField("field$it", FIELD_TYPES[it % FIELD_TYPES.size]) },
  )

  init { RecordRegistry.register(this) }

  override fun encode(value: T, writer: RecordWriter): Unit = throw UnsupportedOperationException()

  override fun decode(reader: RecordReader): T = throw UnsupportedOperationException()
}

private val CODECS: Map<Int, RecordCodec<*>> = mapOf(
  2 to BenchCodec(BenchRecord2::class.java, 2),
  11 to BenchCodec(BenchRecord11::class.java, 11),
  42 to BenchCodec(BenchRecord42::class.java, 42),
)

private class RegistrationState(private val schemaCount: Int) {
  private var ids = IntArray(0)

  fun setUp(fieldCount: Int) {
    ExpoModulesV2.load()
    // Reading `codes` interns the codec; the id is stable from then on. Repeating one id across
    // the batch keeps both arms doing identical work per schema — the only thing a distinct id
    // would change is a fresh map insert instead of a dedup hit, and that is equal on both sides.
    val id = AnyType(TypeDescriptor.Simple(CODECS.getValue(fieldCount).recordClass, false)).codes[1]
    ids = IntArray(schemaCount) { id }
  }

  fun marshal() {
    for (id in ids) {
      RecordRegistry.fetchSchema(id)
    }
  }
}

private const val BATCH_ONE = 1

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class RecordRegistrationSingleBenchmark {
  @Param("2", "11", "42")
  var fieldCount: Int = 0

  private val state = RegistrationState(BATCH_ONE)

  @Setup
  fun setUp() = state.setUp(fieldCount)


  @Benchmark
  @OperationsPerInvocation(BATCH_ONE)
  fun marshal() = state.marshal()

}

private const val BATCH_SMALL = 10

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class RecordRegistrationSmallBatchBenchmark {
  @Param("2", "11", "42")
  var fieldCount: Int = 0

  private val state = RegistrationState(BATCH_SMALL)

  @Setup
  fun setUp() = state.setUp(fieldCount)


  @Benchmark
  @OperationsPerInvocation(BATCH_SMALL)
  fun marshal() = state.marshal()

}

// expo-ui, the largest single module in the expo repo, declares 96 records.
private const val BATCH_MODULE = 96

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class RecordRegistrationModuleBatchBenchmark {
  @Param("2", "11", "42")
  var fieldCount: Int = 0

  private val state = RegistrationState(BATCH_MODULE)

  @Setup
  fun setUp() = state.setUp(fieldCount)


  @Benchmark
  @OperationsPerInvocation(BATCH_MODULE)
  fun marshal() = state.marshal()

}
