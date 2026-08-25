import org.gradle.internal.os.OperatingSystem

plugins {
  alias(libs.plugins.kotlin.jvm)
  // Applies the Kolibri compiler plugin (@NativeMethod rewriting) and adds the kolibri runtime to
  // implementation; the `api(...)` dependency below re-exposes the runtime to our consumers.
  alias(libs.plugins.kolibri)
  `maven-publish`
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
    }
  }
}

val cppDir = layout.projectDirectory.dir("src/main/cpp")
val nativeBuildDir = layout.buildDirectory.dir("native")
val nativeLibsDir = layout.buildDirectory.dir("native-libs")
val nativeBuildType = providers.gradleProperty("nativeBuildType").orElse("Release")

// --- The Hermes host (hermes-tests-environment) --------------------------------------------------
// It creates the `jsi::Runtime` this bridge attaches to, and its library carries the one
// process-wide copy of JSI. Two artifact-only dependencies come with it: the JSI sources to compile
// against (`jsi-cpp`) and the built library to link against (`native-libs`), so nothing here has to
// vendor Hermes.
val hermesEnvJsi: Configuration by configurations.creating
val hermesEnvFbjni: Configuration by configurations.creating
val hermesEnvLibs: Configuration by configurations.creating

val hermesEnvJsiDir = layout.buildDirectory.dir("hermes-env-jsi")
val hermesEnvFbjniDir = layout.buildDirectory.dir("hermes-env-fbjni")
val hermesEnvLibsDir = layout.buildDirectory.dir("hermes-env-libs")

val unpackHermesEnvJsi by tasks.registering(Sync::class) {
  from(provider { zipTree(hermesEnvJsi.singleFile) })
  into(hermesEnvJsiDir)
}

// fbjni: headers only here, the library rides in the same `native-libs` zip. Nothing in this
// project links it — :benchmark compares its JNI dispatch against kolibri's.
val unpackHermesEnvFbjni by tasks.registering(Sync::class) {
  from(provider { zipTree(hermesEnvFbjni.singleFile) })
  into(hermesEnvFbjniDir)
}

val unpackHermesEnvLibs by tasks.registering(Sync::class) {
  from(provider { zipTree(hermesEnvLibs.singleFile) })
  into(hermesEnvLibsDir)
}

// --- Kolibri's C++ sources (the standalone JNI layer) --------------------------------------------
// Kolibri publishes its C++ as a `cpp` classifier zip next to the runtime jar: the <kolibri/...>
// include root plus the CMakeLists.txt our CMakeLists add_subdirectory()s. It is an artifact-only
// dependency, so the classifier notation below is what selects it.
//
// :api unpacks it once for the whole build; :hermes, :test-support and :benchmark read the same
// directory and depend on `unpackKolibriCpp`, the way they already share :api's native-libs dir.
val kolibriCpp: Configuration by configurations.creating

val kolibriCppDir = layout.buildDirectory.dir("kolibri-cpp")

val unpackKolibriCpp by tasks.registering(Sync::class) {
  from(provider { zipTree(kolibriCpp.singleFile) })
  into(kolibriCppDir)
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
  api(libs.kotlin.stdlib)
  // `api` scope: JavaScriptObject/JavaScriptValue publicly extend kolibri's NativeObject, so
  // consumers of this module need kolibri's types on their compile classpath too.
  api(libs.kolibri.runtime)
  // `api` scope: a @JS suspend export's generated trampoline hands the bridge a coroutine, so
  // consumer modules compile against these types.
  api(libs.kotlinx.coroutines.core)
  kolibriCpp("${libs.kolibri.runtime.get().module}:${libs.versions.kolibri.get()}:cpp@zip")
  hermesEnvJsi("${libs.hermes.env.runtime.get().module}:${libs.versions.hermes.env.get()}:jsi-cpp@zip")
  hermesEnvFbjni("${libs.hermes.env.runtime.get().module}:${libs.versions.hermes.env.get()}:fbjni-cpp@zip")
  hermesEnvLibs("${libs.hermes.env.runtime.get().module}:${libs.versions.hermes.env.get()}:native-libs@zip")
  compileOnly(libs.androidx.annotation)
  testImplementation(libs.kotlin.test.junit5)
}

tasks.named<Test>("test") {
  useJUnitPlatform()
}

// --- Native build (CMake + Ninja) ---------------------------------------------------------------

val configureNative by tasks.registering(Exec::class) {
  onlyIf { OperatingSystem.current().isMacOsX }
  dependsOn(unpackKolibriCpp, unpackHermesEnvJsi, unpackHermesEnvLibs)
  inputs.dir(cppDir)
  inputs.dir(kolibriCppDir)
  inputs.dir(hermesEnvJsiDir)
  inputs.dir(hermesEnvLibsDir)
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
    "-DJAVA_HOME=${System.getProperty("java.home")}",
  )
}

val buildNative by tasks.registering(Exec::class) {
  onlyIf { OperatingSystem.current().isMacOsX }
  dependsOn(configureNative, unpackKolibriCpp, unpackHermesEnvJsi, unpackHermesEnvLibs)
  inputs.dir(cppDir)
  inputs.dir(kolibriCppDir)
  inputs.dir(hermesEnvJsiDir)
  inputs.dir(hermesEnvLibsDir)
  inputs.property("nativeBuildType", nativeBuildType)
  outputs.dir(nativeBuildDir)

  workingDir = rootDir
  commandLine("cmake", "--build", nativeBuildDir.get().asFile.path, "--target", "expo-kolibri")
}

val copyNativeLibs by tasks.registering(Copy::class) {
  onlyIf { OperatingSystem.current().isMacOsX }
  dependsOn(buildNative)
  from(nativeBuildDir.map { it.file("libexpo-kolibri.dylib") })
  into(nativeLibsDir)
}

// Make the native libraries available whenever the module is assembled/tested.
tasks.named("classes") { dependsOn(copyNativeLibs) }

// Expose the directories that hold the runtime .dylib files so consumers (e.g. :test-app) can put
// them on java.library.path, and the tasks that produce them. The engine's library sits in its own
// directory because it is unpacked from a published artifact rather than built here.
extra["nativeLibsDir"] = nativeLibsDir.get().asFile
extra["nativeLibsTask"] = copyNativeLibs
extra["hermesEnvLibsDir"] = hermesEnvLibsDir.get().asFile
extra["hermesEnvLibsTask"] = unpackHermesEnvLibs
extra["hermesEnvJsiDir"] = hermesEnvJsiDir.get().asFile
extra["hermesEnvJsiTask"] = unpackHermesEnvJsi
extra["hermesEnvFbjniDir"] = hermesEnvFbjniDir.get().asFile
extra["hermesEnvFbjniTask"] = unpackHermesEnvFbjni

// The compiler plugin, registered straight onto every Kotlin compilation here.
//
// `id("expo.modules.v2")` — what a consuming app applies, and what :gradle-plugin publishes — is
// not available inside this build: a Gradle plugin built here is not on this build's own buildscript
// classpath. Registering the jar plus switching incremental compilation off is the whole of what
// that plugin does, so this is the same thing by hand.
configurations
  .matching { it.name.startsWith("kotlinCompilerPluginClasspath") }
  .configureEach { dependencies.add(project.dependencies.create(project(":compiler-plugin"))) }

// The plugin generates a nested classifier that incremental-compilation caches cannot track, so a
// file that only sees @Record never learns the codec changed.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  incremental = false
}
