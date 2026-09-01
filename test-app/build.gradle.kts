plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.gradle.application)
  // Applies the Kolibri compiler plugin: @NativeMethod externals are rewritten to their generated
  // stubs.
  alias(libs.plugins.kolibri)
}

sourceSets {
  main {
    java.setSrcDirs(listOf("src/main/kotlin"))
    resources.setSrcDirs(listOf("src/main/resources"))
  }
  test {
    java.setSrcDirs(listOf("src/test/kotlin"))
  }
}

dependencies {
  implementation(project(":api"))
  // The desktop test harness: the host-function suite (globalThis.ExpoTestSupport) and the
  // HermesRuntime that pairs a VM from hermes-tests-environment with the modules installed into
  // it. Main.kt's smoke sections use both, so it rides the main classpath rather than
  // testImplementation.
  implementation(project(":test-support"))
  testImplementation(libs.kotlin.test.junit5)
}

application {
  mainClass.set("io.github.expo.modules.v2.testapp.MainKt")
}

// --- Native runtime wiring ----------------------------------------------------------------------
// Directories holding libhermes-test-env.dylib (the Hermes host, unpacked from its published
// artifact into :api's build dir), libexpo-kolibri.dylib (:api, the JSI bridge) and
// libexpo-test-support.dylib (:test-support, the ExpoTestSupport suite).
val nativeLibsDirs = listOf(
  project(":api").layout.buildDirectory.dir("hermes-env-libs"),
  project(":api").layout.buildDirectory.dir("native-libs"),
  project(":test-support").layout.buildDirectory.dir("native-libs"),
)
val nativeLibsPath = nativeLibsDirs.joinToString(File.pathSeparator) { it.get().asFile.path }
val nativeLibsTasks = listOf(
  ":api:unpackHermesEnvLibs",
  ":api:copyNativeLibs",
  ":test-support:copyNativeLibs",
)

// Configure every JVM-launching task to find the native libs. This covers the `application`
// plugin's `run` task as well as the synthetic `...MainKt.main()` tasks IntelliJ generates when
// you click "Run" in the IDE — both are JavaExec, and without this they fail with
// `UnsatisfiedLinkError: no expo-kolibri in java.library.path`.
tasks.withType<JavaExec>().configureEach {
  dependsOn(nativeLibsTasks)
  systemProperty("java.library.path", nativeLibsPath)
}

tasks.named<Test>("test") {
  useJUnitPlatform()
  dependsOn(nativeLibsTasks)
  systemProperty("java.library.path", nativeLibsPath)

  // The dylibs are what these tests actually exercise, so a C++-only change has to invalidate the
  // task. `dependsOn` alone does not: it rebuilds the library and then reports the tests up to date,
  // which silently passes the previous run's result for code that no longer exists.
  inputs.files(nativeLibsDirs)
    .withPropertyName("nativeLibs")
    .withPathSensitivity(PathSensitivity.RELATIVE)
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
