package expo.modules.v2.benchmark.datatype

import expo.modules.v2.annotations.JS
import expo.modules.v2.annotations.Record
import expo.modules.v2.testsupport.HermesRuntime
import expo.modules.v2.modules.Module

/**
 * Focused end-to-end conversion targets shared by JMH and the long-running profiling workload.
 * Every operation crosses JS -> JSI -> JNI -> Kotlin and back to JavaScript.
 */
@JS(name = "DataTypeBench")
class DataTypeBenchModule : Module() {
  @JS
  fun echoBoolean(value: Boolean): Boolean = value

  @JS
  fun echoInt(value: Int): Int = value + 1

  @JS
  fun echoLong(value: Long): Long = value + 1

  @JS
  fun echoDouble(value: Double): Double = value + 0.5

  @JS
  fun echoString(value: String): String = value

  @JS
  fun echoDoubles(value: List<Double>): List<Double> = value

  @JS
  fun echoStrings(value: List<String>): List<String> = value

  @JS
  fun echoDoubleMap(value: Map<String, Double>): Map<String, Double> = value

  @JS
  fun echoStringMap(value: Map<String, String>): Map<String, String> = value

  @JS
  fun echoSmallRecord(value: DataTypeSmallRecord): DataTypeSmallRecord = value

  @JS
  fun echoMixedRecord(value: DataTypeMixedRecord): DataTypeMixedRecord = value

  @JS
  fun echoWideRecord(value: DataTypeWideRecord): DataTypeWideRecord = value
}

@Record
data class DataTypeSmallRecord(val id: Int, val value: Double) : expo.modules.v2.records.Record

@Record
data class DataTypeMixedRecord(
  val id: Int,
  val name: String,
  val value: Double,
  val active: Boolean,
) : expo.modules.v2.records.Record

@Record
data class DataTypeWideRecord(
  val id: Int,
  val name: String,
  val description: String,
  val x: Double,
  val y: Double,
  val z: Double,
  val count: Int,
  val ratio: Double,
  val active: Boolean,
  val tag: String,
  val weight: Double,
  val rank: Int,
) : expo.modules.v2.records.Record

/** JavaScript setup and one numeric sink expression. The expression may use the loop index `i`. */
private data class DataTypeCase(
  val setup: String,
  val operation: String,
)

/** Stable case names are used by both JMH `@Param` values and `-PprofileDataType`. */
val DATA_TYPE_CASE_NAMES: Set<String>
  get() = DATA_TYPE_CASES.keys

private val DATA_TYPE_CASES: Map<String, DataTypeCase> = linkedMapOf(
  "primitive/boolean" to DataTypeCase(
    "const f = DataTypes.echoBoolean",
    "f((i & 1) === 0) ? 1 : 0",
  ),
  "primitive/int" to DataTypeCase(
    "const f = DataTypes.echoInt",
    "f(i & 1023)",
  ),
  "primitive/long" to DataTypeCase(
    "const f = DataTypes.echoLong",
    "f(i * 100000 + 7)",
  ),
  "primitive/double" to DataTypeCase(
    "const f = DataTypes.echoDouble",
    "f(i + 0.25)",
  ),
  "string/ascii[16]" to DataTypeCase(
    "const f = DataTypes.echoString; const p = 'x'.repeat(16)",
    "f(p).length",
  ),
  "string/ascii[1024]" to DataTypeCase(
    "const f = DataTypes.echoString; const p = 'x'.repeat(1024)",
    "f(p).length",
  ),
  "string/utf16[1024]" to DataTypeCase(
    "const f = DataTypes.echoString; const p = 'ż'.repeat(1024)",
    "f(p).length",
  ),
  "list/double[16]" to DataTypeCase(
    "const f = DataTypes.echoDoubles; const p = Array.from({length: 16}, (_, n) => n + 0.25)",
    "f(p).length",
  ),
  "list/double[256]" to DataTypeCase(
    "const f = DataTypes.echoDoubles; const p = Array.from({length: 256}, (_, n) => n + 0.25)",
    "f(p).length",
  ),
  "list/string[64]" to DataTypeCase(
    "const f = DataTypes.echoStrings; " +
      "const p = Array.from({length: 64}, (_, n) => 'value-' + n)",
    "f(p).length",
  ),
  "map/double[16]" to DataTypeCase(
    "const f = DataTypes.echoDoubleMap; " +
      "const p = Object.fromEntries(Array.from({length: 16}, (_, n) => ['v' + n, n + 0.25]))",
    "f(p).v0",
  ),
  "map/double[128]" to DataTypeCase(
    "const f = DataTypes.echoDoubleMap; " +
      "const p = Object.fromEntries(Array.from({length: 128}, (_, n) => ['v' + n, n + 0.25]))",
    "f(p).v0",
  ),
  "map/string[32]" to DataTypeCase(
    "const f = DataTypes.echoStringMap; " +
      "const p = Object.fromEntries(Array.from({length: 32}, (_, n) => ['v' + n, 'value-' + n]))",
    "f(p).v0.length",
  ),
  "record/2-fields" to DataTypeCase(
    "const f = DataTypes.echoSmallRecord; const p = {id: 7, value: 1.25}",
    "f(p).id",
  ),
  "record/4-fields" to DataTypeCase(
    "const f = DataTypes.echoMixedRecord; " +
      "const p = {id: 7, name: 'seven', value: 1.25, active: true}",
    "f(p).id",
  ),
  "record/12-fields" to DataTypeCase(
    "const f = DataTypes.echoWideRecord; " +
      "const p = {id: 7, name: 'seven', description: 'wide record', x: 1.25, y: 2.5, " +
      "z: 3.75, count: 42, ratio: 0.75, active: true, tag: 'bench', weight: 12.5, rank: 3}",
    "f(p).id",
  ),
)

fun registerDataTypeBenchModule(runtime: HermesRuntime) {
  runtime.moduleRegistry.register(DataTypeBenchModule())
  runtime.evaluate("globalThis.DataTypes = expo.modules.DataTypeBench")
}

/** Installs a compiled JS batch so evaluate overhead is amortized in benchmarks and profiles. */
fun installDataTypeBatch(
  runtime: HermesRuntime,
  functionName: String,
  caseName: String,
  operations: Int,
) {
  require(operations > 0) { "operations must be positive" }
  require(functionName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) {
    "invalid JavaScript name: $functionName"
  }
  val case = requireNotNull(DATA_TYPE_CASES[caseName]) {
    "unknown datatype case '$caseName'; expected one of ${DATA_TYPE_CASES.keys.joinToString()}"
  }

  runtime.evaluate(
    """
    globalThis.$functionName = (() => {
      ${case.setup};
      return () => {
        let sink = 0;
        for (let i = 0; i < $operations; i++) sink += ${case.operation};
        return sink;
      };
    })();
    """.trimIndent(),
  )
}
