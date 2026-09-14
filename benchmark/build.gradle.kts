import org.gradle.internal.os.OperatingSystem
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.gradle.application)
  // JMH requires @State classes to be open with a public no-arg constructor; Kotlin classes are
  // final by default, so the all-open plugin unfinalizes them.
  alias(libs.plugins.kotlin.allopen)
  alias(libs.plugins.kotlinx.benchmark)
}

allOpen {
  annotation("org.openjdk.jmh.annotations.State")
}

sourceSets {
  main {
    java.setSrcDirs(listOf("src/main/kotlin"))
  }
  // The JMH suite (src/benchmarks/kotlin), kept apart from the hand-rolled main() harnesses.
  create("benchmarks")
}

dependencies {
  implementation(project(":api"))
  // The test/benchmark harness: HermesRuntime (a VM from hermes-tests-environment plus the
  // modules attached to it) and the host-function suite (globalThis.ExpoTestSupport):
  // __follyBench. Re-exposes :api through its api scope.
  implementation(project(":test-support"))
  // Used directly (binary codec, records); also arrives transitively through :api's api scope.
  implementation(libs.kolibri.runtime)

  // The JMH source set sees everything main sees, plus main's own classes.
  "benchmarksImplementation"(libs.kotlinx.benchmark.runtime)
  "benchmarksImplementation"(sourceSets.main.get().output)
  "benchmarksImplementation"(sourceSets.main.get().runtimeClasspath)
}

// Pass -PbenchmarkFilter=<regex> to run part of the suite, e.g.
// `./gradlew :benchmark:benchmarksBenchmark -PbenchmarkFilter=RecordPair`.
val benchmarkFilter: String? = providers.gradleProperty("benchmarkFilter").orNull

benchmark {
  targets {
    register("benchmarks")
  }
  configurations {
    // The values below reach JMH through OptionsBuilder, so they win over
    // @Warmup/@Measurement/@BenchmarkMode annotations on the classes — keep the run shape here,
    // not in the sources. Only @OperationsPerInvocation and @Param stay in the sources.
    named("main") {
      // `./gradlew :benchmark:benchmarksBenchmark` — the everyday run, one fork per benchmark.
      warmups = 2
      iterations = 3
      iterationTime = 1
      iterationTimeUnit = "s"
      outputTimeUnit = "ns"
      mode = "avgt"
      advanced("jvmForks", "1")
      benchmarkFilter?.let { include(it) }
    }
    // `./gradlew :benchmark:benchmarksDeepBenchmark` — for gating a perf change. Three forks let
    // JMH price the run-to-run drift this machine shows instead of hiding it inside one process.
    // Use advanced("jvmForks", "definedByJmh") to hand fork control to per-class @Fork instead.
    register("deep") {
      warmups = 3
      iterations = 5
      iterationTime = 1
      iterationTimeUnit = "s"
      outputTimeUnit = "ns"
      mode = "avgt"
      advanced("jvmForks", "3")
      benchmarkFilter?.let { include(it) }
    }
    // `./gradlew :benchmark:benchmarksSmokeBenchmark` — a fast correctness check of the harness
    // (does every benchmark still run?), not a source of numbers.
    register("smoke") {
      warmups = 1
      iterations = 1
      iterationTime = 300
      iterationTimeUnit = "ms"
      outputTimeUnit = "ns"
      mode = "avgt"
      advanced("jvmForks", "1")
      benchmarkFilter?.let { include(it) }
    }
  }
}

application {
  mainClass.set("io.github.expo.modules.v2.benchmark.NativeBenchmarkKt")
}

// --- Native build (CMake + Ninja) ---------------------------------------------------------------

// macOS and Linux: the two hosts hermes-test-environment publishes for. The library names differ
// only in their suffix; CMake picks it, and `copyNativeLibs` below has to agree.
val desktopNativeEnabled =
  (OperatingSystem.current().isMacOsX || OperatingSystem.current().isLinux) &&
    !providers.gradleProperty("skipDesktopNative").isPresent
val sharedLibrarySuffix = if (OperatingSystem.current().isMacOsX) ".dylib" else ".so"
// The JNI-dispatch micro-benchmark (JniCallBenchmark.cpp) compares kolibri's JavaClass tokens
// against fbjni, so it lives in its own small library: libexpo-benchmark.dylib links against the
// :api bridge (kolibri symbols) and against the fbjni that hermes-test-environment ships.

val cppDir = layout.projectDirectory.dir("src/main/cpp")
val nativeBuildDir = layout.buildDirectory.dir("native")
val nativeLibsDir = layout.buildDirectory.dir("native-libs")
val nativeBuildType = providers.gradleProperty("nativeBuildType").orElse("Release")

// Kolibri's C++ sources, unpacked from the published `cpp` zip by :api:unpackKolibriCpp.
val kolibriCppDir = project(":api").layout.buildDirectory.dir("kolibri-cpp")
val apiNativeLibsDir = project(":api").layout.buildDirectory.dir("native-libs")
// The Hermes host's libraries (libhermes-test-env.dylib and libfbjni.dylib) and fbjni's headers,
// unpacked from its published artifacts by :api:unpackHermesEnvLibs / :api:unpackHermesEnvFbjni.
val hermesEnvLibsDir = project(":api").layout.buildDirectory.dir("hermes-env-libs")
val hermesEnvFbjniDir = project(":api").layout.buildDirectory.dir("hermes-env-fbjni")
val testSupportNativeLibsDir = project(":test-support").layout.buildDirectory.dir("native-libs")

val configureNative by tasks.registering(Exec::class) {
  onlyIf { desktopNativeEnabled }
  // The imported libexpo-kolibri.dylib and libfbjni.dylib must exist before CMake configures the
  // imported targets.
  dependsOn(
    ":api:copyNativeLibs",
    ":api:unpackKolibriCpp",
    ":api:unpackHermesEnvFbjni",
    ":api:unpackHermesEnvLibs",
  )
  inputs.dir(cppDir)
  inputs.property("nativeBuildType", nativeBuildType)
  outputs.dir(nativeBuildDir)

  workingDir = rootDir
  commandLine(
    "cmake",
    "-S", cppDir.asFile.path,
    "-B", nativeBuildDir.get().asFile.path,
    "-G", "Ninja",
    "-DCMAKE_BUILD_TYPE=${nativeBuildType.get()}",
    "-DFBJNI_CPP_DIR=${hermesEnvFbjniDir.get().asFile.path}",
    "-DHERMES_ENV_LIBS_DIR=${hermesEnvLibsDir.get().asFile.path}",
    "-DKOLIBRI_DIR=${kolibriCppDir.get().asFile.path}",
    "-DEXPO_API_LIBS_DIR=${apiNativeLibsDir.get().asFile.path}",
    "-DJAVA_HOME=${System.getProperty("java.home")}",
  )
}

val buildNative by tasks.registering(Exec::class) {
  onlyIf { desktopNativeEnabled }
  dependsOn(
    configureNative,
    ":api:copyNativeLibs",
    ":api:unpackKolibriCpp",
    ":api:unpackHermesEnvFbjni",
    ":api:unpackHermesEnvLibs",
  )
  inputs.dir(cppDir)
  inputs.property("nativeBuildType", nativeBuildType)
  outputs.dir(nativeBuildDir)

  workingDir = rootDir
  // `-k 0`: Ninja keeps going past a failing translation unit, so one build reports every error.
  commandLine("cmake", "--build", nativeBuildDir.get().asFile.path, "--target", "expo-benchmark", "--", "-k", "0")
}

val copyNativeLibs by tasks.registering(Copy::class) {
  onlyIf { desktopNativeEnabled }
  dependsOn(buildNative)
  from(nativeBuildDir.map { it.file("libexpo-benchmark$sharedLibrarySuffix") })
  into(nativeLibsDir)
}

if (desktopNativeEnabled) {
  tasks.named("classes") { dependsOn(copyNativeLibs) }
}

// --- Native runtime wiring (mirrors :test-app) --------------------------------------------------
// The benchmarks load libhermes-test-env.dylib + libfbjni.dylib (the Hermes host),
// libexpo-kolibri.dylib (:api), libexpo-test-support.dylib (:test-support) and
// libexpo-benchmark.dylib (this module) via System.loadLibrary, so every JVM-launching task must
// point java.library.path at all four directories and depend on the tasks that produce them.
tasks.withType<JavaExec>().configureEach {
  dependsOn(
    ":api:copyNativeLibs",
    ":api:unpackHermesEnvLibs",
    ":test-support:copyNativeLibs",
    "copyNativeLibs",
  )
  systemProperty(
    "java.library.path",
    listOf(hermesEnvLibsDir, apiNativeLibsDir, testSupportNativeLibsDir, nativeLibsDir)
      .joinToString(File.pathSeparator) { it.get().asFile.path }
  )
}

// The two remaining main() harnesses time their work inside C++ (the native hooks run their own
// loops and return a finished report), so JMH has nothing to measure for them. Everything timed
// from the JVM side lives in src/benchmarks/kotlin.

// Runs the JNI-dispatch report (NativeBenchmark.kt).
tasks.register<JavaExec>("runNativeBenchmark") {
  mainClass.set("io.github.expo.modules.v2.benchmark.NativeBenchmarkKt")
  classpath = sourceSets["main"].runtimeClasspath
}

// Runs the folly::dynamic comparison (FollyBenchmark.kt; needs :test-support's native build
// configured with -DEXPO_FOLLY_BENCHMARK=ON).
tasks.register<JavaExec>("runFollyBenchmark") {
  mainClass.set("io.github.expo.modules.v2.benchmark.FollyBenchmarkKt")
  classpath = sourceSets["main"].runtimeClasspath
}

// --- End-to-end module profiling ---------------------------------------------------------------
// The workload is separate from JMH on purpose: it runs one stable scenario long enough for JFR,
// Instruments or async-profiler to collect useful stacks. JMH remains the source of benchmark
// numbers; this runner explains where that time goes.
val profileScenario = providers.gradleProperty("profileScenario").orElse("hot")
val profileWarmupSeconds = providers.gradleProperty("profileWarmupSeconds").orElse("5")
val profileDurationSeconds = providers.gradleProperty("profileDurationSeconds").orElse("30")
val profileBatchSize = providers.gradleProperty("profileBatchSize").orElse("1000")
val profileDataType = providers.gradleProperty("profileDataType").orElse("primitive/int")

fun JavaExec.configureModulesProfileWorkload() {
  group = "benchmark"
  mainClass.set("io.github.expo.modules.v2.benchmark.e2e.ModulesE2eProfileKt")
  classpath = sourceSets["main"].runtimeClasspath
  doFirst {
    systemProperty("expo.profile.scenario", profileScenario.get())
    systemProperty("expo.profile.warmup.seconds", profileWarmupSeconds.get())
    systemProperty("expo.profile.duration.seconds", profileDurationSeconds.get())
    systemProperty("expo.profile.batch.size", profileBatchSize.get())
    systemProperty("expo.profile.datatype", profileDataType.get())
  }
}

tasks.register<JavaExec>("runModulesProfileWorkload") {
  description = "Runs the modules e2e workload long enough to attach an external profiler."
  configureModulesProfileWorkload()
}

val profileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  .withZone(ZoneOffset.UTC)
  .format(Instant.now())
val defaultProfileOutput = layout.buildDirectory
  .file("profiles/modules-e2e-$profileTimestamp.jfr")
  .map { it.asFile.absolutePath }
val profileOutput = providers.gradleProperty("profileOutput")
  .orElse(defaultProfileOutput)
  .map { rootProject.file(it).absolutePath }

tasks.register<JavaExec>("profileModulesJfr") {
  description = "Profiles the modules e2e workload and writes a Java Flight Recorder file."
  configureModulesProfileWorkload()
  jvmArgs("-XX:FlightRecorderOptions=stackdepth=256")
  doFirst {
    systemProperty("expo.profile.jfr.path", profileOutput.get())
  }
}

val profileAsyncEvent = providers.gradleProperty("profileAsyncEvent").orElse("cpu")
val profileAsyncCommand = providers.gradleProperty("profileAsyncCommand").orElse("asprof")
val profileAsyncFormat = providers.gradleProperty("profileAsyncFormat").orElse("tree")
val profileAsyncInterval = providers.gradleProperty("profileAsyncInterval").orElse("10ms")
val profileAsyncAllocInterval = providers.gradleProperty("profileAsyncAllocInterval").orElse("64k")
val defaultAsyncProfileOutput = layout.buildDirectory
  .file("profiles/modules-$profileTimestamp-${profileAsyncEvent.get()}.html")
  .map { it.asFile.absolutePath }
val profileAsyncOutput = providers.gradleProperty("profileAsyncOutput")
  .orElse(defaultAsyncProfileOutput)
  .map { rootProject.file(it).absolutePath }

tasks.register<JavaExec>("profileModulesAsync") {
  description = "Profiles a modules workload and writes an async-profiler report (HTML call tree by default)."
  configureModulesProfileWorkload()
  doFirst {
    systemProperty("expo.profile.async.command", profileAsyncCommand.get())
    systemProperty("expo.profile.async.event", profileAsyncEvent.get())
    systemProperty("expo.profile.async.format", profileAsyncFormat.get())
    systemProperty("expo.profile.async.interval", profileAsyncInterval.get())
    systemProperty("expo.profile.async.alloc.interval", profileAsyncAllocInterval.get())
    systemProperty("expo.profile.async.path", profileAsyncOutput.get())
  }
}

// The compiler plugin, registered straight onto every Kotlin compilation here.
//
// `id("io.github.expo.modules.v2")` — what a consuming app applies, and what :gradle-plugin
// publishes — is not available inside this build: a Gradle plugin built here is not on this
// build's own buildscript classpath. Registering the jar plus switching incremental compilation
// off is the whole of what that plugin does, so this is the same thing by hand.
configurations
  .matching { it.name.startsWith("kotlinCompilerPluginClasspath") }
  .configureEach { dependencies.add(project.dependencies.create(project(":compiler-plugin"))) }

// The plugin generates a nested classifier that incremental-compilation caches cannot track, so a
// file that only sees @Record never learns the codec changed.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  incremental = false
}
