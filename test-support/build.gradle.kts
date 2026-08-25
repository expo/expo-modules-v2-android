import org.gradle.internal.os.OperatingSystem

plugins {
  alias(libs.plugins.kotlin.jvm)
}

sourceSets {
  main {
    java.setSrcDirs(listOf("src/main/kotlin"))
  }
}

dependencies {
  // `api` scope: TestSupport.install takes the :api JavaScriptRuntime, so consumers need :api's
  // types on their compile classpath too.
  api(project(":api"))
  // The Hermes host: HermesRuntime here creates one of its VMs and attaches the modules to it.
  // `api` scope, because a consumer that wants the engine handle itself resolves it from here.
  api(libs.hermes.env.runtime)
}

// --- Native build (CMake + Ninja) ---------------------------------------------------------------
// The test/benchmark host functions (globalThis.ExpoTestSupport) live in their own library so the
// :api bridge dylib ships pure library code: libexpo-test-support.dylib links against the :api
// bridge (kolibri, jsi and converter symbols) and installs the suite into a runtime on demand.

val cppDir = layout.projectDirectory.dir("src/main/cpp")
val nativeBuildDir = layout.buildDirectory.dir("native")
val nativeLibsDir = layout.buildDirectory.dir("native-libs")
val nativeBuildType = providers.gradleProperty("nativeBuildType").orElse("Release")

// The Hermes host's JSI headers and library, unpacked from its published artifacts by
// :api:unpackHermesEnvJsi / :api:unpackHermesEnvLibs.
val hermesEnvJsiDir = project(":api").layout.buildDirectory.dir("hermes-env-jsi")
val hermesEnvLibsDir = project(":api").layout.buildDirectory.dir("hermes-env-libs")
// Kolibri's C++ sources, unpacked from the published `cpp` zip by :api:unpackKolibriCpp.
val kolibriCppDir = project(":api").layout.buildDirectory.dir("kolibri-cpp")
val apiCppDir = project(":api").layout.projectDirectory.dir("src/main/cpp")
val apiNativeLibsDir = project(":api").layout.buildDirectory.dir("native-libs")

val configureNative by tasks.registering(Exec::class) {
  onlyIf { OperatingSystem.current().isMacOsX }
  // The imported libexpo-kolibri.dylib must exist before CMake configures the imported target.
  dependsOn(
    ":api:copyNativeLibs",
    ":api:unpackKolibriCpp",
    ":api:unpackHermesEnvJsi",
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
    "-DJSI_DIR=${hermesEnvJsiDir.get().asFile.path}",
    "-DHERMES_ENV_LIBS_DIR=${hermesEnvLibsDir.get().asFile.path}",
    "-DKOLIBRI_DIR=${kolibriCppDir.get().asFile.path}",
    "-DEXPO_API_CPP_DIR=${apiCppDir.asFile.path}",
    "-DEXPO_API_LIBS_DIR=${apiNativeLibsDir.get().asFile.path}",
    "-DJAVA_HOME=${System.getProperty("java.home")}",
  )
}

val buildNative by tasks.registering(Exec::class) {
  onlyIf { OperatingSystem.current().isMacOsX }
  dependsOn(configureNative, ":api:copyNativeLibs", ":api:unpackKolibriCpp")
  inputs.dir(cppDir)
  inputs.property("nativeBuildType", nativeBuildType)
  // TestSupportObject includes API bridge headers such as JSFunctions.h. Treat them as task
  // inputs so Gradle lets Ninja recompile after an API-side native state/layout change.
  inputs.dir(apiCppDir)
  outputs.dir(nativeBuildDir)

  workingDir = rootDir
  commandLine("cmake", "--build", nativeBuildDir.get().asFile.path, "--target", "expo-test-support")
}

val copyNativeLibs by tasks.registering(Copy::class) {
  onlyIf { OperatingSystem.current().isMacOsX }
  dependsOn(buildNative)
  from(nativeBuildDir.map { it.file("libexpo-test-support.dylib") })
  into(nativeLibsDir)
}

tasks.named("classes") { dependsOn(copyNativeLibs) }

// Expose the directory that holds the runtime .dylib so consumers (:test-app, :benchmark) can put
// it on java.library.path, and the task that produces it.
extra["nativeLibsDir"] = nativeLibsDir.get().asFile
extra["nativeLibsTask"] = copyNativeLibs
