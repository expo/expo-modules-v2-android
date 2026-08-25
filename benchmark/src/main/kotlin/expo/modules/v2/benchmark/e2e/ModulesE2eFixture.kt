package expo.modules.v2.benchmark.e2e

import expo.modules.v2.annotations.JS
import expo.modules.v2.annotations.Record
import expo.modules.v2.testsupport.HermesRuntime
import expo.modules.v2.modules.Module

const val E2E_FIRST_WORKFLOW_RESULT = 688.52

/** A line in the representative request passed through the end-to-end module workload. */
@Record
data class E2eLine(
  val id: Int,
  val units: Int,
  val unitPrice: Double,
  val enabled: Boolean,
) : expo.modules.v2.records.Record

/** A nested record: the list and every line cross the binary bridge in one payload. */
@Record
data class E2eRequest(
  val requestId: Int,
  val lines: List<E2eLine>,
  val taxRate: Double,
) : expo.modules.v2.records.Record

/** The first module's result is passed straight into the second module by JavaScript. */
@Record
data class E2eResult(
  val requestId: Int,
  val acceptedUnits: Int,
  val total: Double,
  val summary: String,
) : expo.modules.v2.records.Record

@JS(name = "E2eProcessor")
class E2eProcessorModule : Module() {
  @JS
  val revision: Int = 2

  @JS
  fun process(request: E2eRequest): E2eResult {
    var acceptedUnits = 0
    var subtotal = 0.0
    for (line in request.lines) {
      if (line.enabled) {
        acceptedUnits += line.units
        subtotal += line.units * line.unitPrice
      }
    }

    return E2eResult(
      requestId = request.requestId,
      acceptedUnits = acceptedUnits,
      total = subtotal * (1.0 + request.taxRate),
      summary = "${request.requestId}:$acceptedUnits",
    )
  }
}

@JS(name = "E2eMetrics")
class E2eMetricsModule : Module() {
  private var batches = 0

  @JS
  val batchCount: Int
    get() = batches

  @JS
  fun record(result: E2eResult): Double {
    // Keep state changing so the engine and bridge cannot treat the workflow as a constant.
    batches = (batches + 1) and 0x3fffffff
    return result.total + result.acceptedUnits + (batches and 7)
  }
}

/**
 * JavaScript setup shared by JMH and the profiler workload.
 *
 * One `workflow()` operation crosses JS -> Kotlin -> JS through [E2eProcessorModule], then sends
 * that record through [E2eMetricsModule], and finally reads a property from each module. The
 * payload is allocated once so the benchmark measures module work rather than fixture creation.
 */
val E2E_WORKFLOW_PRELUDE =
  """
  const processor = expo.modules.E2eProcessor;
  const metrics = expo.modules.E2eMetrics;
  const request = {
    requestId: 7,
    lines: Array.from({length: 24}, (_, i) => ({
      id: i,
      units: (i % 4) + 1,
      unitPrice: i + 0.5,
      enabled: (i % 3) !== 0,
    })),
    taxRate: 0.23,
  };
  const workflow = () => {
    const result = processor.process(request);
    return metrics.record(result) + processor.revision + metrics.batchCount;
  };
  """.trimIndent()

fun registerE2eModules(runtime: HermesRuntime) {
  runtime.moduleRegistry.register(E2eProcessorModule())
  runtime.moduleRegistry.register(E2eMetricsModule())
}

/** Runs the fresh-runtime path: both modules materialize and every used export resolves once. */
fun runFirstE2eWorkflow(runtime: HermesRuntime): Double =
  runtime.evaluate(
    """
    (() => {
      $E2E_WORKFLOW_PRELUDE
      return workflow();
    })()
    """.trimIndent(),
  ).getDouble()

fun checkFirstE2eWorkflow(result: Double) {
  check(kotlin.math.abs(result - E2E_FIRST_WORKFLOW_RESULT) < 0.000_001) {
    "end-to-end workflow: expected $E2E_FIRST_WORKFLOW_RESULT, got $result"
  }
}

/** Compiles a profiler-friendly batch so one evaluate call contains [operations] full workflows. */
fun installE2eBatch(runtime: HermesRuntime, name: String, operations: Int) {
  require(operations > 0) { "operations must be positive" }
  require(name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "invalid JavaScript name: $name" }

  runtime.evaluate(
    """
    globalThis.$name = (() => {
      $E2E_WORKFLOW_PRELUDE
      return () => {
        let sink = 0;
        for (let i = 0; i < $operations; i++) sink += workflow();
        return sink;
      };
    })();
    """.trimIndent(),
  )
}
