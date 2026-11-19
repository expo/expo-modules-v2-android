package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.Enumerable
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.Tag
import io.github.expo.modules.v2.Union
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.testsupport.HermesRuntime
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
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import org.openjdk.jmh.annotations.OperationsPerInvocation

@Union
sealed interface BenchDimension {
  @JvmInline
  value class Points(val value: Double) : BenchDimension

  @Tag("auto")
  data object Auto : BenchDimension

  @Record
  data class Percent(val percent: Double) : BenchDimension
}

@Union
sealed interface BenchAmount {
  @JvmInline
  value class Whole(val value: Int) : BenchAmount

  @JvmInline
  value class Fraction(val value: Double) : BenchAmount
}

@Union
sealed interface BenchShape {
  @Record
  data class Circle(val radius: Double) : BenchShape

  @Record
  data class Rect(val width: Double, val height: Double) : BenchShape
}

@Union
sealed interface BenchItems {
  @JvmInline
  value class Counts(val values: List<Int>) : BenchItems

  @JvmInline
  value class Words(val values: List<String>) : BenchItems
}

@Record
data class BenchSize(val width: Double, val height: Double)

/** Binary data or a record: an object that is no ArrayBuffer goes to the record. */
@Union
sealed interface BenchBlob {
  @JvmInline
  value class Bytes(val bytes: ByteArray) : BenchBlob

  @JvmInline
  value class Size(val size: BenchSize) : BenchBlob
}

/** Maps told apart by what they hold. */
@Union
sealed interface BenchTally {
  @JvmInline
  value class Totals(val totals: Map<String, Int>) : BenchTally

  @JvmInline
  value class Names(val names: Map<String, String>) : BenchTally
}

/** Two variants that both cross as a string: a string URL refuses goes to String. */
@Union
sealed interface BenchLink {
  @JvmInline
  value class Address(val url: URL) : BenchLink

  @JvmInline
  value class Text(val text: String) : BenchLink
}

enum class BenchTier(val value: Int) : Enumerable { LOW(0), HIGH(1) }

/** An Int-backed enum or any other Int: both cross as an Int. */
@Union
sealed interface BenchRank {
  @JvmInline
  value class Named(val tier: BenchTier) : BenchRank

  @JvmInline
  value class Custom(val value: Int) : BenchRank
}

@ExpoSharedObject
class BenchTextRef(text: StringBuilder) : SharedRef<StringBuilder>(text)

@ExpoSharedObject
class BenchCountRef(count: AtomicInteger) : SharedRef<AtomicInteger>(count)

/** Refs of one class: only Kotlin sees what each carries. */
@Union
sealed interface BenchRefs {
  @JvmInline
  value class OfText(val ref: SharedRef<StringBuilder>) : BenchRefs

  @JvmInline
  value class OfCount(val ref: SharedRef<AtomicInteger>) : BenchRefs
}

@ExpoSharedObject
class BenchFirstObject : SharedObject()

@ExpoSharedObject
class BenchSecondObject : SharedObject()

/** Shared objects told apart by their class. */
@Union
sealed interface BenchHandle {
  @JvmInline
  value class First(val value: BenchFirstObject) : BenchHandle

  @JvmInline
  value class Second(val value: BenchSecondObject) : BenchHandle
}

/**
 * Union arguments, JS -> Kotlin: where native code picks the variant. Each function returns a
 * constant, so the return path adds the same small cost to every case. The `Jni` twins pin the
 * JNI object path; the others take the transport the plugin picks.
 */
@ExpoModule(name = "Uni")
class UnionOps : Module() {
  @JS
  fun dimension(value: BenchDimension): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun dimensionJni(value: BenchDimension): Int = 1

  @JS
  fun amount(value: BenchAmount): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun amountJni(value: BenchAmount): Int = 1

  @JS
  fun shape(value: BenchShape): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun shapeJni(value: BenchShape): Int = 1

  @JS
  fun items(value: BenchItems): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun itemsJni(value: BenchItems): Int = 1

  @JS
  fun dimensions(values: List<BenchDimension>): Int = values.size

  @JS
  fun blob(value: BenchBlob): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun blobJni(value: BenchBlob): Int = 1

  @JS
  fun tally(value: BenchTally): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun tallyJni(value: BenchTally): Int = 1

  // A shared object never rides the buffer, so both rows take the JNI path.
  @JS
  fun handle(value: BenchHandle): Int = 1

  @JS
  fun handleJni(value: BenchHandle): Int = 1

  @JS
  fun second(): BenchSecondObject = BenchSecondObject()

  @JS
  fun rank(value: BenchRank): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun rankJni(value: BenchRank): Int = 1

  // A shared object never rides the buffer, so both rows take the JNI path.
  @JS
  fun refs(value: BenchRefs): Int = 1

  @JS
  fun refsJni(value: BenchRefs): Int = 1

  @JS
  fun textRef(): SharedRef<StringBuilder> = BenchTextRef(StringBuilder("t"))

  @JS
  fun countRef(): SharedRef<AtomicInteger> = BenchCountRef(AtomicInteger(1))

  @JS
  fun link(value: BenchLink): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun linkJni(value: BenchLink): Int = 1

  @JS
  @BufferMode(Buffer.NO)
  fun dimensionsJni(values: List<BenchDimension>): Int = values.size
}

/** A payload and the function it goes to; the `Jni` twin is that name plus `Jni`. */
private class UnionCase(val payload: String, val function: String)

private val UNION_CASES = mapOf(
  // Matches the first variant.
  "dimension/number" to UnionCase("12.5", "dimension"),
  // A literal, after one number variant.
  "dimension/literal" to UnionCase("'auto'", "dimension"),
  // A record, after a number variant and a literal.
  "dimension/record" to UnionCase("({percent: 0.5})", "dimension"),
  // Matches the first of two number variants.
  "amount/whole" to UnionCase("7", "amount"),
  // Refused by the first number variant.
  "amount/fraction" to UnionCase("7.5", "amount"),
  // An object without the first record's required field.
  "shape/second-record" to UnionCase("({width: 2, height: 3})", "shape"),
  // An array of strings, refused by List<Int> at its first element.
  "items/words[10]" to UnionCase("Array.from({length: 10}, (_, i) => 'w' + i)", "items"),
  // An array of 100 numbers, matching List<Int>.
  "items/counts[100]" to UnionCase("Array.from({length: 100}, (_, i) => i)", "items"),
  // An object that is no ArrayBuffer, after a ByteArray variant.
  "blob/record" to UnionCase("({width: 2, height: 3})", "blob"),
  // A map of strings, after a map of Ints.
  "tally/second-map" to UnionCase("({a: 'x', b: 'y'})", "tally"),
  // A shared object of the second variant's class.
  "handle/second-class" to UnionCase("Uni.second()", "handle"),
  // A string the first variant takes.
  "link/url" to UnionCase("'https://expo.dev/a'", "link"),
  // A string the first variant refuses, which Kotlin hands to the second.
  "link/text" to UnionCase("'hello'", "link"),
  // An entry of the Int-backed enum, the first variant.
  "rank/named" to UnionCase("1", "rank"),
  // An Int the enum refuses, which Kotlin hands to the second variant.
  "rank/custom" to UnionCase("7", "rank"),
  // A ref the first variant takes.
  "refs/first" to UnionCase("Uni.textRef()", "refs"),
  // A ref the first variant refuses, which Kotlin hands to the second.
  "refs/second" to UnionCase("Uni.countRef()", "refs"),
  // 100 elements cycling through the three variants.
  "list/mixed[100]" to UnionCase(
    "Array.from({length: 100}, (_, i) => [i + 0.5, 'auto', {percent: i}][i % 3])",
    "dimensions",
  ),
)

private class UnionState(ops: Int) {
  private val js = JsLoopRuntime(ops)

  fun setUp(name: String) {
    val case = requireNotNull(UNION_CASES[name]) { "unknown case: $name" }
    registerUnionModule(js.open())
    js.install("__auto", "const p = ${case.payload}", "Uni.${case.function}(p)")
    js.install("__jni", "const p = ${case.payload}", "Uni.${case.function}Jni(p)")
  }

  fun auto(): JavaScriptValue = js.run("__auto")

  fun jni(): JavaScriptValue = js.run("__jni")

  fun close() = js.close()
}

internal fun registerUnionModule(runtime: HermesRuntime) {
  runtime.moduleRegistry.register(UnionOps())
  runtime.evaluate("globalThis.Uni = expo.modules.Uni")
}

private const val OPS_UNION_FAST = 50_000

/** One union value per call. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class UnionFastBenchmark {
  @Param(
    "dimension/number",
    "dimension/literal",
    "dimension/record",
    "amount/whole",
    "amount/fraction",
    "shape/second-record",
  )
  var case: String = ""

  private val state = UnionState(OPS_UNION_FAST)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_UNION_FAST)
  fun auto(): JavaScriptValue = state.auto()

  @Benchmark
  @OperationsPerInvocation(OPS_UNION_FAST)
  fun jni(): JavaScriptValue = state.jni()
}

private const val OPS_UNION_MID = 5_000

/** Arrays inside a union, and a list of unions. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class UnionMidBenchmark {
  @Param("items/words[10]", "items/counts[100]", "list/mixed[100]")
  var case: String = ""

  private val state = UnionState(OPS_UNION_MID)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_UNION_MID)
  fun auto(): JavaScriptValue = state.auto()

  @Benchmark
  @OperationsPerInvocation(OPS_UNION_MID)
  fun jni(): JavaScriptValue = state.jni()
}

/** A value the first variant of its kind refuses, for each kind a union pre-checks. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class UnionMissBenchmark {
  @Param("blob/record", "tally/second-map", "handle/second-class")
  var case: String = ""

  private val state = UnionState(OPS_UNION_FAST)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_UNION_FAST)
  fun auto(): JavaScriptValue = state.auto()

  @Benchmark
  @OperationsPerInvocation(OPS_UNION_FAST)
  fun jni(): JavaScriptValue = state.jni()
}

/** Unions whose variants all cross as the same bridge type: Kotlin picks the variant. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class UnionSameBridgeBenchmark {
  @Param("link/url", "link/text", "rank/named", "rank/custom", "refs/first", "refs/second")
  var case: String = ""

  private val state = UnionState(OPS_UNION_FAST)

  @Setup
  fun setUp() = state.setUp(case)

  @TearDown
  fun tearDown() = state.close()

  @Benchmark
  @OperationsPerInvocation(OPS_UNION_FAST)
  fun auto(): JavaScriptValue = state.auto()

  @Benchmark
  @OperationsPerInvocation(OPS_UNION_FAST)
  fun jni(): JavaScriptValue = state.jni()
}
