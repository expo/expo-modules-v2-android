package expo.modules.v2.benchmark.jmh

import expo.modules.v2.benchmark.e2e.E2E_WORKFLOW_PRELUDE
import expo.modules.v2.benchmark.e2e.checkFirstE2eWorkflow
import expo.modules.v2.benchmark.e2e.registerE2eModules
import expo.modules.v2.benchmark.e2e.runFirstE2eWorkflow
import expo.modules.v2.testsupport.HermesRuntime
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
import org.openjdk.jmh.annotations.OperationsPerInvocation

/**
 * Representative Expo modules workloads measured across the complete Hermes -> JSI -> JNI ->
 * Kotlin path.
 *
 * The hot and fresh-runtime paths are separate JMH states so the startup measurement does not
 * leave an unrelated Hermes runtime alive beside the runtime it is timing.
 */
private const val HOT_OPS = 1_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class ModuleEndToEndHotBenchmark {
  private val hot = JsLoopRuntime(HOT_OPS)

  @Setup
  fun setUp() {
    registerE2eModules(hot.open())
    hot.install("__modulesE2e", E2E_WORKFLOW_PRELUDE, "workflow()")
  }

  @TearDown
  fun tearDown() = hot.close()

  @Benchmark
  @OperationsPerInvocation(HOT_OPS)
  fun hotWorkflow(): JavaScriptValue = hot.run("__modulesE2e")
}

/**
 * A fresh Hermes runtime, Kotlin registration, JavaScript compilation, lazy module materialization,
 * first export resolution, one complete workflow, and runtime destruction.
 *
 * Native libraries and process-global caches are warm, as they should be for repeatable JMH
 * numbers; use the `startup` profiling scenario to inspect the phases of each fresh lifecycle.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class ModuleEndToEndStartupBenchmark {
  @Setup
  fun setUp() {
    NativeLibraries.load()
    // Validate the fixture outside the measured method so a broken result fails setup.
    HermesRuntime().use { runtime ->
      registerE2eModules(runtime)
      checkFirstE2eWorkflow(runFirstE2eWorkflow(runtime))
    }
  }

  @Benchmark
  fun runtimeStartupAndFirstWorkflow(): Double =
    HermesRuntime().use { runtime ->
      registerE2eModules(runtime)
      runFirstE2eWorkflow(runtime)
    }
}
