import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.gradle.api.component.AdhocComponentWithVariants

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildconfig)
  alias(libs.plugins.gradle.java.test.fixtures)
  alias(libs.plugins.gradle.idea)
  // Published, because a consuming app must not compile this: it is pure JVM, while the rest of
  // this repo is built from source there against the `jsi` binary the app ships. The POM and signing
  // come from the root build.
  alias(libs.plugins.vanniktech.mavenPublish)
}

mavenPublishing {
  // Prefixed for the same reason :react is: `io.github.expo` is a namespace shared across Expo's
  // releases, and `compiler-plugin` on its own says nothing about which project it belongs to.
  coordinates(artifactId = "expo-modules-v2-compiler-plugin")

  // Maven Central rejects a deployment that has no `-javadoc.jar` next to the main artifact, so the
  // jar has to be published even though this project renders no API docs. `JavadocJar.Empty()`
  // ships an empty one, which satisfies the validator.
  configure(KotlinJvm(JavadocJar.Empty(), sourcesJar = true))

  pom {
    description = "The Expo Modules API v2 Kotlin compiler plugin"
  }
}

// `java-test-fixtures` wires its variants into the `java` component, so the test-fixtures jar and
// its sources jar would be published too. They exist only for this project's own test framework —
// nothing outside the build consumes them, and every published file counts against Maven Central's
// per-organization file-count limit.
(components["java"] as AdhocComponentWithVariants).let { java ->
  listOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements")
    .forEach { java.withVariantsFromConfiguration(configurations[it]) { skip() } }
}

val kotlinVersionString: String = libs.versions.kotlin.get()
val kotlinVersion: KotlinVersion = parseKotlinVersion(kotlinVersionString)

// A compiler plugin is bound to the exact compiler it was built against, so this artifact is
// published once per supported Kotlin release as `<expo-modules-v2>-<kotlin>`. The Gradle plugin
// picks the one that matches the consumer's Kotlin.
version = "${libs.versions.expo.modules.v2.get()}-$kotlinVersionString"

val compatSourceDirs: List<File> =
  compatSourceDirsFor(layout.projectDirectory.dir("compat").asFile, kotlinVersion)
val testFixturesCompatSourceDirs: List<File> =
  compatSourceDirsFor(layout.projectDirectory.dir("test-fixtures-compat").asFile, kotlinVersion)

val testDataDir: Directory = testDataDirFor(layout.projectDirectory, kotlinVersion)
val testGenDirectory = layout.buildDirectory.dir("test-gen")

sourceSets {
  main {
    java.setSrcDirs(listOf("src"))
    kotlin.srcDirs(compatSourceDirs)
    resources.setSrcDirs(listOf("resources"))
  }
  testFixtures {
    java.setSrcDirs(listOf("test-fixtures"))
    kotlin.srcDirs(testFixturesCompatSourceDirs)
  }
  test {
    java.setSrcDirs(listOf("test", testGenDirectory))
    resources.setSrcDirs(listOf(testDataDir))
  }
}

idea {
  // This is needed until IDEA fixes IDEA-339729.
  module.generatedSourceDirs.add(testGenDirectory.get().asFile)
}

val testArtifacts: Configuration by configurations.creating

// The runtime the box tests compile against: `io.github.expo.modules.v2.records.*`, `@Record`, the
// TypeDescriptor hierarchy. :api never depends on :compiler-plugin, so this is not a cycle — the
// Gradle plugin only injects the compiler jar into :api's *compile* classpath.
val recordsRuntime: Configuration by configurations.creating

dependencies {
  compileOnly(libs.kotlin.compiler)

  recordsRuntime(project(":api"))

  testFixturesApi(libs.kotlin.test.junit5)
  testFixturesApi(libs.kotlin.test.framework)
  testFixturesApi(libs.kotlin.compiler)
  testFixturesRuntimeOnly(libs.junit)

  // Dependencies required to run the internal test framework.
  testArtifacts(libs.kotlin.stdlib.jdk8)
  testArtifacts(libs.kotlin.reflect)
  testArtifacts(libs.kotlin.test)
  testArtifacts(libs.kotlin.script.runtime)
  testArtifacts(libs.kotlin.annotations.jvm)
}

buildConfig {
  useKotlinOutput {
    internalVisibility = true
  }

  packageName("io.github.expo.modules.v2.compiler")
  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"io.github.expo.modules.v2.compiler\"")
}

tasks.test {
  val apiFiles = files(recordsRuntime)

  dependsOn(testArtifacts)
  inputs.files(testArtifacts).withPropertyName("testArtifacts")
  inputs.files(apiFiles).withPropertyName("recordsRuntime")

  useJUnitPlatform()
  workingDir = rootDir

  jvmArgumentProviders.add(
    CommandLineArgumentProvider { listOf("-DrecordsRuntime.classpath=${apiFiles.asPath}") },
  )

  // Properties required to run the internal test framework.
  setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
  setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
  setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
  setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
  setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
  setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

  systemProperty("idea.ignore.disabled.plugins", "true")
  systemProperty("idea.home.path", rootDir)

  // Regenerate the golden .txt dumps in testData instead of asserting against them:
  //   ./gradlew :compiler-plugin:test -PupdateTestData
  if (providers.gradleProperty("updateTestData").isPresent) {
    systemProperty("kotlin.test.update.test.data", "true")
    outputs.upToDateWhen { false }
  }
}

kotlin {
  compilerOptions {
    optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    // Kotlin 2.2 gated the checker, FIR-symbol and IR surfaces this plugin is built on behind
    // these markers. They are still the only way to express what the plugin does, so opt in
    // rather than chase replacements that are not settled yet.
    optIn.add("org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi")
    optIn.add("org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess")
    optIn.add("org.jetbrains.kotlin.fir.symbols.SymbolInternals")
    // From 2.2.20 the FIR checker and diagnostics APIs are declared with context parameters, which
    // the compiler only lets us implement or call once the feature is enabled. It was experimental
    // until it became part of the language in 2.4, where the flag is redundant (and warns).
    if (kotlinVersion >= KotlinVersion(2, 2, 20) && kotlinVersion < KotlinVersion(2, 4, 0)) {
      freeCompilerArgs.add("-Xcontext-parameters")
    }
  }
}

val generateTests by tasks.registering(JavaExec::class) {
  inputs.dir(testDataDir)
    .withPropertyName("testData")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(testGenDirectory)
    .withPropertyName("generatedTests")

  classpath = sourceSets.testFixtures.get().runtimeClasspath
  mainClass.set("io.github.expo.modules.v2.compiler.GenerateTestsKt")
  workingDir = rootDir
  args(
    listOf(
      testGenDirectory.get().asFile.absolutePath,
      testDataDir.asFile.absolutePath,
    ),
  )
}

tasks.compileTestKotlin {
  dependsOn(generateTests)
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
  val path = testArtifacts.files
    .find { """$jarName-\d.*""".toRegex().matches(it.name) }
    ?.absolutePath
    ?: return
  systemProperty(propName, path)
}

/** "2.3.20", "2.3", or a pre-release like "2.5.0-Beta1" (the qualifier is ignored). */
fun parseKotlinVersion(version: String): KotlinVersion {
  val parts = version.substringBefore("-").split(".").map { part ->
    part.toIntOrNull() ?: error("Not a Kotlin version: '$version'")
  }
  require(parts.size in 2..3) { "Not a Kotlin version: '$version'" }
  return KotlinVersion(parts[0], parts[1], parts.getOrElse(2) { 0 })
}

/** `testData-<since>/` for the newest `<since>` that [kotlin] satisfies, else the baseline `testData/`. */
fun testDataDirFor(projectDir: Directory, kotlin: KotlinVersion): Directory {
  val tiers = projectDir.asFile.listFiles { file -> file.isDirectory && file.name.startsWith("testData-") }.orEmpty()
    .associateBy { parseKotlinVersion(it.name.removePrefix("testData-")) }
  val tier = tiers.filterKeys { it <= kotlin }.maxByOrNull { it.key }?.value
  return projectDir.dir(tier?.name ?: "testData")
}

/** One directory per concern under [compatRoot]: the newest `<since>` variant that [kotlin] satisfies. */
fun compatSourceDirsFor(compatRoot: File, kotlin: KotlinVersion): List<File> {
  val concerns = compatRoot.listFiles { file -> file.isDirectory }?.sortedBy { it.name }.orEmpty()
  return concerns.map { concern ->
    val variants = concern.listFiles { file -> file.isDirectory }.orEmpty()
      .associateBy { parseKotlinVersion(it.name) }
    variants.filterKeys { it <= kotlin }.maxByOrNull { it.key }?.value
      ?: error(
        "compiler-plugin/${compatRoot.name}/${concern.name}: no variant supports Kotlin $kotlin " +
          "(oldest is ${variants.keys.minOrNull()})."
      )
  }
}
