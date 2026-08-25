import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.gradle.api.component.AdhocComponentWithVariants

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildconfig)
  alias(libs.plugins.gradle.java.test.fixtures)
  alias(libs.plugins.gradle.idea)
  // Published, because a consuming app must not compile this: it is pure JVM, while the rest of
  // this repo is built from source there against the `jsi` binary the app ships. The POM, signing
  // and checksum pruning come from the root build.
  alias(libs.plugins.vanniktech.mavenPublish)
}

mavenPublishing {
  // Maven Central rejects a deployment that has no `-javadoc.jar` next to the main artifact, so the
  // jar has to be published even though this project renders no API docs. `JavadocJar.Empty()`
  // ships an empty one, which satisfies the validator.
  configure(KotlinJvm(JavadocJar.Empty(), sourcesJar = true))
}

// `java-test-fixtures` wires its variants into the `java` component, so the test-fixtures jar and
// its sources jar would be published too. They exist only for this project's own test framework —
// nothing outside the build consumes them, and every published file counts against Maven Central's
// per-organization file-count limit.
(components["java"] as AdhocComponentWithVariants).let { java ->
  listOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements")
    .forEach { java.withVariantsFromConfiguration(configurations[it]) { skip() } }
}

val testDataDir = layout.projectDirectory.dir("testData")
val testGenDirectory = layout.buildDirectory.dir("test-gen")

sourceSets {
  main {
    java.setSrcDirs(listOf("src"))
    resources.setSrcDirs(listOf("resources"))
  }
  testFixtures {
    java.setSrcDirs(listOf("test-fixtures"))
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

// The runtime the box tests compile against: `expo.modules.v2.records.*`, `@Record`, the
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
  testArtifacts(libs.kotlin.stdlib)
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

  packageName("expo.modules.v2.compiler")
  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"expo.modules.v2.compiler\"")
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
  }
}

val generateTests by tasks.registering(JavaExec::class) {
  inputs.dir(testDataDir)
    .withPropertyName("testData")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(testGenDirectory)
    .withPropertyName("generatedTests")

  classpath = sourceSets.testFixtures.get().runtimeClasspath
  mainClass.set("expo.modules.v2.compiler.GenerateTestsKt")
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
