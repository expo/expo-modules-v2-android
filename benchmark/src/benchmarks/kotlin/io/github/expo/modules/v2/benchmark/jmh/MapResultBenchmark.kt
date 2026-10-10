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
 * What an export costs per call when it returns maps, the way `getConstants()` and list results
 * do: the values are built once, so a score is the call plus the conversion of the result into
 * JavaScript objects. The other transport benchmarks return a count instead, so they leave this
 * direction out.
 *
 * A `/buffer` case returns the same value as the case without it, through `Buffer.YES`: maps of
 * `Any?` values cross in a JNI slot unless the export opts in to the buffer.
 *
 * The other cases price the per-runtime cache of key names (255 keys). A `/full` case first
 * fills it with the keys of `wide()`, which its loop never returns again, so the keys the loop
 * needs must take the place of keys that are no longer used. `wide[512]` returns twice as many
 * keys as the cache holds on every call: the worst case for a cache that evicts.
 */
private const val OPS = 10_000

@ExpoModule(name = "MapResultBench")
private class MapResultOps : Module() {
  private val constants: Map<String, Any?> = mapOf(
    "Version" to 36,
    "Release" to "16",
    "Serial" to "unknown",
    "Fingerprint" to "OnePlus/LE2123/OnePlus9Pro:16/release-keys",
    "Model" to "LE2123",
    "Manufacturer" to "OnePlus",
    "Brand" to "OnePlus",
    "isTesting" to false,
    "isDisableAnimations" to false,
    "reactNativeVersion" to mapOf("major" to 0, "minor" to 84, "patch" to 0),
    "uiMode" to "normal",
    "fontScale" to 1.0,
  )

  private val rows: List<Map<String, Any?>> = List(100) { i ->
    mapOf("id" to i, "name" to "n$i", "x" to i * 0.5, "flag" to (i % 2 == 0))
  }

  private val typed: Map<String, Double> = (0 until 12).associate { "metric$it" to it * 1.5 }

  private val wide: Map<String, Int> = (0 until 512).associate { "wide$it" to it }

  @JS
  fun constants(): Map<String, Any?> = constants

  @JS
  @BufferMode(returns = Buffer.YES)
  fun constantsBuffered(): Map<String, Any?> = constants

  @JS
  fun rows(): List<Map<String, Any?>> = rows

  @JS
  @BufferMode(returns = Buffer.YES)
  fun rowsBuffered(): List<Map<String, Any?>> = rows

  @JS
  fun typed(): Map<String, Double> = typed

  @JS
  fun wide(): Map<String, Int> = wide
}

private val MAP_RESULT_CASES = mapOf(
  "constants[12]" to "M.constants().Model.length",
  "constants[12]/buffer" to "M.constantsBuffered().Model.length",
  "rows[100]" to "M.rows().length",
  "rows[100]/buffer" to "M.rowsBuffered().length",
  "typed[12]" to "M.typed().metric3",
  "typed[12]/full" to "M.typed().metric3",
  "wide[512]" to "M.wide().wide7",
)

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class MapResultBenchmark {
  @Param(
    "constants[12]",
    "constants[12]/buffer",
    "rows[100]",
    "rows[100]/buffer",
    "typed[12]",
    "typed[12]/full",
    "wide[512]",
  )
  var case: String = ""

  private val js = JsLoopRuntime(OPS)

  @Setup
  fun setUp() {
    val call = requireNotNull(MAP_RESULT_CASES[case]) { "unknown case: $case" }
    val runtime = js.open()
    runtime.moduleRegistry.register(MapResultOps())
    val fill = if (case.endsWith("/full")) "; M.wide()" else ""
    js.install("__mapResult", "const M = expo.modules.MapResultBench$fill", call)
  }

  @TearDown
  fun tearDown() = js.close()

  @Benchmark
  @OperationsPerInvocation(OPS)
  fun call(): JavaScriptValue = js.run("__mapResult")
}
