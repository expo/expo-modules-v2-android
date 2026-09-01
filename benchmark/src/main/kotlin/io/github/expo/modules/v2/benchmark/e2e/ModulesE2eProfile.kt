package io.github.expo.modules.v2.benchmark.e2e

import io.github.expo.modules.v2.benchmark.datatype.DATA_TYPE_CASE_NAMES
import io.github.expo.modules.v2.benchmark.datatype.installDataTypeBatch
import io.github.expo.modules.v2.benchmark.datatype.registerDataTypeBenchModule
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import jdk.jfr.Category
import jdk.jfr.Configuration
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.Recording
import jdk.jfr.StackTrace
import kotlin.system.exitProcess

private const val PROFILE_FUNCTION = "__profileModulesBatch"
private const val PROFILE_DATATYPE_FUNCTION = "__profileDatatypeBatch"

@Name("io.github.expo.modules.v2.ProfilePhase")
@Label("Expo Modules v2 profile phase")
@Category("Expo Modules v2")
@StackTrace(false)
private class ProfilePhaseEvent : Event() {
  @JvmField
  @Label("Phase")
  var phase: String = ""

  @JvmField
  @Label("Logical operations")
  var operations: Long = 0
}

private inline fun <T> profilePhase(name: String, operations: Long, block: () -> T): T {
  val event = ProfilePhaseEvent().apply {
    phase = name
    this.operations = operations
  }
  event.begin()
  return try {
    block()
  } finally {
    event.end()
    event.commit()
  }
}

private interface ProfileScenario : AutoCloseable {
  val operationsPerStep: Long

  fun step(): Double
}

private class HotScenario(batchSize: Int) : ProfileScenario {
  private val runtime = HermesRuntime()

  override val operationsPerStep = batchSize.toLong()

  init {
    registerE2eModules(runtime)
    installE2eBatch(runtime, PROFILE_FUNCTION, batchSize)
    check(step() > 0.0) { "hot end-to-end workflow returned no work" }
  }

  override fun step(): Double =
    profilePhase("hot-workflow-batch", operationsPerStep) {
      runtime.evaluate("$PROFILE_FUNCTION()").getDouble()
    }

  override fun close() = runtime.close()
}

private class StartupScenario : ProfileScenario {
  override val operationsPerStep = 1L

  init {
    checkFirstE2eWorkflow(step())
  }

  override fun step(): Double {
    val runtime = profilePhase("runtime-create", 1) { HermesRuntime() }
    return try {
      profilePhase("module-register", 1) { registerE2eModules(runtime) }
      profilePhase("first-workflow", 1) { runFirstE2eWorkflow(runtime) }
    } finally {
      profilePhase("runtime-close", 1) { runtime.close() }
    }
  }

  override fun close() = Unit
}

private class DataTypeScenario(batchSize: Int, caseName: String) : ProfileScenario {
  private val runtime = HermesRuntime()

  override val operationsPerStep = batchSize.toLong()

  init {
    registerDataTypeBenchModule(runtime)
    installDataTypeBatch(runtime, PROFILE_DATATYPE_FUNCTION, caseName, batchSize)
    check(step() > 0.0) { "datatype '$caseName' returned no work" }
  }

  override fun step(): Double =
    profilePhase("datatype-batch", operationsPerStep) {
      runtime.evaluate("$PROFILE_DATATYPE_FUNCTION()").getDouble()
    }

  override fun close() = runtime.close()
}

private data class RunStats(
  val operations: Long,
  val sink: Double,
  val elapsedNanos: Long,
  val allocatedBytes: Long?,
)

private val threadAllocationBean =
  (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.takeIf {
    it.isThreadAllocatedMemorySupported
  }

private fun currentThreadAllocatedBytes(): Long? {
  val bean = threadAllocationBean ?: return null
  if (!bean.isThreadAllocatedMemoryEnabled) {
    try {
      bean.isThreadAllocatedMemoryEnabled = true
    } catch (_: UnsupportedOperationException) {
      return null
    } catch (_: SecurityException) {
      return null
    }
  }
  return bean.getThreadAllocatedBytes(Thread.currentThread().id).takeIf { it >= 0 }
}

private fun exercise(scenario: ProfileScenario, duration: Duration): RunStats {
  if (duration.isZero) return RunStats(0, 0.0, 0, 0)

  val allocatedBefore = currentThreadAllocatedBytes()
  val started = System.nanoTime()
  val deadline = started + duration.toNanos()
  var operations = 0L
  var sink = 0.0
  do {
    sink += scenario.step()
    operations += scenario.operationsPerStep
  } while (System.nanoTime() < deadline)
  val elapsedNanos = System.nanoTime() - started
  val allocatedBytes = allocatedBefore?.let { before ->
    currentThreadAllocatedBytes()?.minus(before)
  }
  return RunStats(operations, sink, elapsedNanos, allocatedBytes)
}

private fun positiveSeconds(property: String, default: Long, allowZero: Boolean = false): Duration {
  val value = System.getProperty(property)?.toLongOrNull() ?: default
  require(value > 0 || (allowZero && value == 0L)) {
    "$property must be ${if (allowZero) "zero or positive" else "positive"}, got $value"
  }
  return Duration.ofSeconds(value)
}

private fun runProfilerCommand(command: List<String>) {
  val exitCode = ProcessBuilder(command)
    .inheritIO()
    .start()
    .waitFor()
  check(exitCode == 0) { "Profiler command failed ($exitCode): ${command.joinToString(" ")}" }
}

private inline fun <T> withAsyncProfiler(
  command: String,
  event: String,
  format: String,
  interval: String,
  allocationInterval: String,
  output: Path,
  block: () -> T,
): T {
  require(command.isNotBlank()) { "expo.profile.async.command must not be blank" }
  require(event.isNotBlank()) { "expo.profile.async.event must not be blank" }
  require(interval.isNotBlank()) { "expo.profile.async.interval must not be blank" }
  require(allocationInterval.isNotBlank()) {
    "expo.profile.async.alloc.interval must not be blank"
  }
  require(format in setOf("flat", "traces", "collapsed", "flamegraph", "tree", "otlp")) {
    "Unsupported async-profiler output format '$format'"
  }
  val absoluteOutput = output.toAbsolutePath()
  absoluteOutput.parent?.let { Files.createDirectories(it) }
  val pid = ProcessHandle.current().pid().toString()

  val startCommand = mutableListOf(command, "start", "-e", event, "-j", "256")
  when (event) {
    "alloc" -> startCommand += listOf("--alloc", allocationInterval)
    "nativemem" -> Unit
    else -> startCommand += listOf("-i", interval)
  }
  startCommand += pid
  runProfilerCommand(startCommand)
  return try {
    block()
  } finally {
    runProfilerCommand(
      listOf(command, "stop", "-o", format, "-f", absoluteOutput.toString(), pid),
    )
    println("async-profiler $event $format profile: $absoluteOutput")
  }
}

private fun runProfile() {
  val scenarioName = System.getProperty("expo.profile.scenario", "hot")
  val dataTypeName = System.getProperty("expo.profile.datatype", "primitive/int")
  val warmup = positiveSeconds("expo.profile.warmup.seconds", 5, allowZero = true)
  val duration = positiveSeconds("expo.profile.duration.seconds", 30)
  val batchSize = System.getProperty("expo.profile.batch.size", "1000").toInt()
  require(batchSize > 0) { "expo.profile.batch.size must be positive, got $batchSize" }

  ExpoHermes.ensureLoaded()
  val scenario: ProfileScenario = when (scenarioName) {
    "hot" -> HotScenario(batchSize)
    "startup" -> StartupScenario()
    "datatype" -> DataTypeScenario(batchSize, dataTypeName)
    else -> error("Unknown profile scenario '$scenarioName'; expected 'hot', 'startup' or 'datatype'")
  }

  scenario.use {
    println(
      "Modules profiler PID=${ProcessHandle.current().pid()} scenario=$scenarioName" +
        (if (scenarioName == "datatype") " datatype=$dataTypeName" else "") +
        " warmup=${warmup.seconds}s duration=${duration.seconds}s",
    )
    println("Attach an external profiler now; the measured workload starts after warmup.")
    exercise(scenario, warmup)

    val jfrOutput = System.getProperty("expo.profile.jfr.path")?.let { Path.of(it) }
    val asyncOutput = System.getProperty("expo.profile.async.path")?.let { Path.of(it) }
    require(jfrOutput == null || asyncOutput == null) {
      "JFR and async-profiler output cannot be enabled in the same run"
    }
    val stats = when {
      asyncOutput != null -> withAsyncProfiler(
        command = System.getProperty("expo.profile.async.command", "asprof"),
        event = System.getProperty("expo.profile.async.event", "cpu"),
        format = System.getProperty("expo.profile.async.format", "tree"),
        interval = System.getProperty("expo.profile.async.interval", "10ms"),
        allocationInterval = System.getProperty("expo.profile.async.alloc.interval", "64k"),
        output = asyncOutput,
      ) {
        exercise(scenario, duration)
      }
      jfrOutput != null ->
        Recording(Configuration.getConfiguration("profile")).use { recording ->
          recording.name = "expo-modules-v2-$scenarioName"
          recording.start()
          val recorded = exercise(scenario, duration)
          recording.stop()
          jfrOutput.toAbsolutePath().parent?.let { Files.createDirectories(it) }
          recording.dump(jfrOutput)
          println("JFR recording: ${jfrOutput.toAbsolutePath()}")
          recorded
        }
      else -> exercise(scenario, duration)
    }

    val elapsedSeconds = stats.elapsedNanos / 1_000_000_000.0
    val throughput = stats.operations / elapsedSeconds
    val allocationSummary = stats.allocatedBytes?.let { allocatedBytes ->
      val bytesPerOperation = allocatedBytes.toDouble() / stats.operations
      ", allocated=$allocatedBytes bytes (%.1f B/op)".format(bytesPerOperation)
    }.orEmpty()
    println(
      "Profiled ${stats.operations} logical operations in %.3fs (%.1f ops/s), sink=%.3f%s"
        .format(elapsedSeconds, throughput, stats.sink, allocationSummary),
    )
  }
}

fun main() {
  try {
    runProfile()
  } catch (t: Throwable) {
    if (t is IllegalArgumentException && t.message?.contains("unknown datatype case") == true) {
      System.err.println("Available datatype cases: ${DATA_TYPE_CASE_NAMES.joinToString()}")
    }
    t.printStackTrace()
    exitProcess(1)
  }
  exitProcess(0)
}
