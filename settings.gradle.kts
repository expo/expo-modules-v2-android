pluginManagement {
  repositories {
    mavenLocal()
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenLocal()
    google()
    mavenCentral()
  }
}

rootProject.name = "expo-modules-v2"

// --- The Hermes host -----------------------------------------------------------------------------
// `io.github.expo:hermes-test-environment` creates the `jsi::Runtime` everything here attaches to,
// and ships the one process-wide copy of JSI. It is not released yet, so it is resolved from
// mavenLocal out of a sibling checkout — and published from here, rather than by hand, so a plain
// `./gradlew :test-app:run` in a fresh clone works.
//
// This has to happen while settings are evaluated: by the time :api's tasks exist, Gradle already
// resolves the configurations that need the artifact. The publish is skipped unless a source file
// in the host is newer than what it last published, so it costs nothing on a normal build. Bumping
// the host's Hermes submodule is not tracked — publish it by hand for that.
//
//   -PhermesEnvDir=<path>       use a checkout somewhere else
//   -PskipHermesEnvBuild        leave mavenLocal alone and use whatever it already holds
run {
  if (providers.gradleProperty("skipHermesEnvBuild").isPresent) {
    return@run
  }

  val checkout = File(
    rootDir,
    providers.gradleProperty("hermesEnvDir").getOrElse("../hermes-tests-environment")
  )
  val launcher = File(checkout, "gradlew")
  if (!launcher.canExecute()) {
    logger.lifecycle("hermes-test-environment: no checkout at $checkout, using mavenLocal as is")
    return@run
  }

  val published = File(
    System.getProperty("user.home"),
    ".m2/repository/io/github/expo/hermes-test-environment"
  ).walkTopDown()
    .filter { it.isFile && it.name.endsWith("-native-libs.zip") }
    .maxOfOrNull { it.lastModified() }
    ?: 0L
  val newestSource = sequenceOf(
    File(checkout, "runtime/src"),
    File(checkout, "runtime/build.gradle.kts"),
    File(checkout, "gradle/libs.versions.toml"),
  ).flatMap { it.walkTopDown() }
    .filter { it.isFile }
    .maxOfOrNull { it.lastModified() }
    ?: 0L

  if (newestSource <= published) {
    return@run
  }

  logger.lifecycle("hermes-test-environment: publishing to mavenLocal from $checkout")
  val exit = ProcessBuilder(launcher.path, "--quiet", ":runtime:publishToMavenLocal")
    .directory(checkout)
    .inheritIO()
    .start()
    .waitFor()
  check(exit == 0) {
    "hermes-test-environment failed to publish (exit $exit). Fix it in $checkout, or pass " +
      "-PskipHermesEnvBuild to build against whatever mavenLocal already holds."
  }
}

include("api")
// The React Native integration: :api's Kotlin and C++ compiled for Android, attaching to the
// `jsi::Runtime` React Native created instead of creating one. Published to mavenLocal for an app
// to consume, alongside the compiler toolchain below.
include("react")
// The compiler toolchain. Both are published, because a consuming app must not build them: they are
// pure JVM, while everything else here is compiled from source in that app against the `jsi` binary
// it ships. Publish with `./gradlew publishToolchainToMavenLocal`.
include("compiler-plugin")
include("gradle-plugin")
include("test-app")
include("test-support")
include("benchmark")
