package expo.modules.v2.benchmark.jmh

import expo.modules.v2.testsupport.HermesRuntime
import expo.modules.v2.jsi.JavaScriptObject
import expo.modules.v2.jsi.JavaScriptValue
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

/**
 * The @NativePointer transform measured under JMH.
 *
 * [JavaScriptValue.isNumber] and [JavaScriptValue.getDouble] are rewritten by the compiler plugin
 * to native stubs that take `mNativePointer` directly, skipping the JNI `GetLongField`.
 * [JavaScriptObject.setProperty] with a [JavaScriptValue] argument is the handle-passing path
 * (`nativePointerOf` before the stub call, `reachabilityFenceOf` after); the `Double` overload does
 * the same JSI `setProperty` work with no handle argument, so it is the control row.
 *
 * The runtime is created in [setUp] and closed in [tearDown], both of which JMH runs on the same
 * worker thread that runs the measured methods — jsi::Runtime is thread-affine.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class JsiValueBenchmark {
  private lateinit var runtime: HermesRuntime
  private lateinit var global: JavaScriptObject
  private lateinit var number: JavaScriptValue

  @Setup
  fun setUp() {
    NativeLibraries.load()
    runtime = HermesRuntime()
    runtime.evaluate("globalThis.n = 42;")
    global = runtime.global()
    number = global.getProperty("n")
    check(number.isNumber()) { "isNumber() should be true for 42" }
    check(number.getDouble() == 42.0) { "getDouble() should be 42.0, got ${number.getDouble()}" }
  }

  @TearDown
  fun tearDown() {
    runtime.close()
  }

  @Benchmark
  fun isNumber(): Boolean = number.isNumber()

  @Benchmark
  fun getDouble(): Double = number.getDouble()

  /** Handle-passing path: the argument is an @AsNativePointer JS value. */
  @Benchmark
  fun setJSValueProperty() {
    global.setProperty("fenced", number)
  }

  /** Control for [setJSValueProperty]: same JSI work, no handle argument. */
  @Benchmark
  fun setDoubleProperty() {
    global.setProperty("plain", 1.0)
  }
}
