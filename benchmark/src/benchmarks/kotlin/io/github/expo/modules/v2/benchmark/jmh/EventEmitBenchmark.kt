package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.Module
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
import org.openjdk.jmh.annotations.OperationsPerInvocation

/**
 * What one native event emit costs, from Kotlin to the JavaScript listeners and back.
 *
 * JMH's worker thread is the runtime's JavaScript thread, so [Event.emit] delivers inline: nothing
 * is posted and nothing is drained, and the loop times only the emit path itself - resolving the
 * emitting object, decoding the payload and calling each listener.
 */
private const val OPS = 1_000

@ExpoModule(name = "EventBench")
private class EventOps : Module() {
  /** A scalar payload: a JNI slot. */
  @Event
  val onCount = event<Int>()

  /** A string payload: a JNI slot holding a reference. */
  @Event
  val onText = event<String>()
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class EventEmitBenchmark {
  @Param("int/1-listener", "int/3-listeners", "string/1-listener")
  var case: String = ""

  private lateinit var runtime: HermesRuntime
  private lateinit var ops: EventOps
  private lateinit var emitOne: (Int) -> Unit

  @Setup
  fun setUp() {
    NativeLibraries.load()
    runtime = HermesRuntime()
    ops = EventOps()
    runtime.moduleRegistry.register(ops)

    val (payload, listeners) = case.split('/')
    val event = if (payload == "int") "count" else "text"
    val count = listeners.substringBefore('-').toInt()
    runtime.evaluate(
      """
      globalThis.sink = 0;
      for (let i = 0; i < $count; i++) {
        expo.modules.EventBench.addListener('$event', () => { sink++; });
      }
      """.trimIndent(),
    )
    emitOne = if (payload == "int") {
      { ops.onCount.emit(it) }
    } else {
      { ops.onText.emit("event") }
    }

    // A case whose listeners never run should fail setup, not produce a number.
    emitOne(0)
    check(runtime.evaluate("sink").getDouble() == count.toDouble()) { "the listeners did not run" }
  }

  @TearDown
  fun tearDown() = runtime.close()

  @Benchmark
  @OperationsPerInvocation(OPS)
  fun emit() {
    for (i in 0 until OPS) {
      emitOne(i)
    }
  }
}
