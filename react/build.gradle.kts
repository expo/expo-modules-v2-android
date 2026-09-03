import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  // No version: the Kotlin Gradle plugin is already on this build's classpath (the JVM modules
  // request it by version), and asking again with one fails the plugin resolution.
  id("org.jetbrains.kotlin.android")
  // Applies the Kolibri compiler plugin (@NativeMethod rewriting), adds the kolibri runtime, and —
  // because this is an Android project — the kolibri-android Prefab AAR plus prefab consumption, so
  // the CMake build below can `find_package(kolibri-android)`.
  alias(libs.plugins.kolibri)
  // This is the one artifact a consuming app depends on rather than builds. The POM boilerplate,
  // signing and checksum pruning come from the root build.
  alias(libs.plugins.vanniktech.mavenPublish)
}

mavenPublishing {
  // The project name alone would publish as `io.github.expo:react`. That namespace is shared with
  // everything else Expo releases through it, so the artifact carries the project it belongs to.
  coordinates(artifactId = "expo-modules-v2-react")

  // `release` only — a consumer never wants the debug variant, and every published file counts
  // against Maven Central's per-organization file-count limit. Central's validator rejects a
  // deployment with no `-javadoc.jar`, so `publishJavadocJar` stays on; AGP generates it from this
  // variant's sources.
  configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))

  pom {
    description = "Expo Modules API v2 for React Native on Android: the Kotlin bridge plus, as a " +
      "`cpp` classifier zip, the C++ the consuming app compiles against the `jsi` it ships"
  }
}

// This module compiles :api's sources rather than copying them: one Kotlin surface and one C++
// bridge, two host shapes. Only the pieces that differ — attaching to a runtime the host owns, and
// posting to the host's JS thread — live under this module's own src/main.
val apiKotlinDir = rootProject.layout.projectDirectory.dir("api/src/main/kotlin")
val apiCppDir = rootProject.layout.projectDirectory.dir("api/src/main/cpp")

android {
  namespace = "io.github.expo.modules.v2.react"

  // Matches what Expo SDK 57 / React Native 0.86 pin (react-native/gradle/libs.versions.toml), so
  // the AAR merges into a consuming app without forcing anything up.
  compileSdk = 36
  ndkVersion = "27.1.12297006"

  defaultConfig {
    minSdk = 24

    ndk {
      abiFilters += listOf("arm64-v8a")
    }

    externalNativeBuild {
      cmake {
        arguments += listOf(
          // The STL kolibri-android's prefab metadata demands, and the one React Native uses.
          "-DANDROID_STL=c++_shared",
          "-DEXPO_API_CPP_DIR=${apiCppDir.asFile.path}",
          "-DEXPO_MODULES_V2_WERROR=ON",
        )
        targets += "expo-kolibri"
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  sourceSets {
    named("main") {
      kotlin.setSrcDirs(listOf(apiKotlinDir, layout.projectDirectory.dir("src/main/kotlin")))
    }
  }

  packaging {
    jniLibs {
      excludes += listOf(
        "**/libjsi.so",
        "**/libc++_shared.so",
        "**/libexpo-kolibri.so",
      )
    }
  }
}

kotlin {
  jvmToolchain(17)
  compilerOptions {
    jvmTarget = JvmTarget.JVM_11
  }
}

dependencies {
  // `api` scope for the same reason :api uses it: JavaScriptObject/JavaScriptValue publicly extend
  // kolibri's NativeObject, and a suspend export's trampoline hands the bridge a coroutine.
  api(libs.kolibri.runtime)
  api(libs.kotlinx.coroutines.core)
  compileOnly(libs.androidx.annotation)

  // React Native supplies both halves of the host: `libjsi.so` (through the `jsi` prefab this
  // module's CMake links against) and the ReactContext the scheduler posts to. `compileOnly` is not
  // an option — prefab only sees runtime/implementation dependencies.
  implementation(libs.react.android)

  api(libs.kolibri.android)
}

val cppSourcesZip by tasks.registering(Zip::class) {
  archiveClassifier = "cpp"

  from(apiCppDir) {
    include("expo-modules-v2/**", "expo-jsi/**")
  }
  from(layout.projectDirectory.dir("src/main/cpp")) {
    include("expo-modules-v2-react/**", "CMakeLists.txt")
  }
}

publishing.publications.withType<MavenPublication>()
  .matching { it.name == "maven" }
  .configureEach { artifact(cppSourcesZip) }

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
