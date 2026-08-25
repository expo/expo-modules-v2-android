package expo.modules.v2.benchmark.jmh

import expo.modules.v2.testsupport.HermesRuntime
import expo.modules.v2.jsi.JavaScriptValue

/**
 * The harness for benchmarks whose measured op happens inside JavaScript.
 *
 * There is no Kotlin-side API for calling an already-compiled JS function, so every invocation
 * goes through [HermesRuntime.evaluate]. To keep that fixed cost off the measurement, the loop is
 * compiled once in `@Setup` and stored as a global; an invocation then evaluates only `__name()`.
 * What is left — compiling that one call expression — is what `EvaluateOverheadBenchmark` reports,
 * and [ops] is chosen per benchmark class so it stays under ~1% of the invocation.
 *
 * The payload is built once, in the closure that produces the loop, exactly as the `main()`
 * harnesses built it once outside their timed region. The loop body therefore reads the payload
 * from an enclosing scope rather than a local `const`; that is uniform across every case.
 *
 * `jsi::Runtime` is thread-affine. JMH runs `@Setup`, the measured methods and `@TearDown` on the
 * same worker thread, so the runtime stays on the thread that created it.
 */
internal class JsLoopRuntime(private val ops: Int) {
  lateinit var runtime: HermesRuntime
    private set

  fun open(): HermesRuntime {
    NativeLibraries.load()
    runtime = HermesRuntime()
    return runtime
  }

  /**
   * Compiles the measured loop into `globalThis.[name]`.
   *
   * [prelude] runs once and prepares whatever [callExpr] references (typically `const p = ...`).
   * [callExpr] must produce a number, so the loop can accumulate a sink the engine cannot drop.
   */
  fun install(name: String, prelude: String, callExpr: String) {
    runtime.evaluate(
      """
      globalThis.$name = (() => {
        $prelude;
        return () => {
          let sink = 0;
          for (let i = 0; i < $ops; i++) sink += $callExpr;
          return sink;
        };
      })();
      """.trimIndent(),
    )
    // Run it once: a case that throws should fail setup, not produce a number.
    runtime.evaluate("$name()")
  }

  fun run(name: String): JavaScriptValue = runtime.evaluate("$name()")

  fun close() {
    runtime.close()
  }
}
