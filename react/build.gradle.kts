plugins {
  alias(libs.plugins.android.library)
  // No version: the Kotlin Gradle plugin is already on this build's classpath (the JVM modules
  // request it by version), and asking again with one fails the plugin resolution.
  id("org.jetbrains.kotlin.android")
  // Applies the Kolibri compiler plugin (@NativeMethod rewriting), adds the kolibri runtime, and —
  // because this is an Android project — the kolibri-android Prefab AAR plus prefab consumption, so
  // the CMake build below can `find_package(kolibri-android)`.
  alias(libs.plugins.kolibri)
  `maven-publish`
}

group = "expo.modules.v2"
version = "0.1.0-SNAPSHOT"

// This module compiles :api's sources rather than copying them: one Kotlin surface and one C++
// bridge, two host shapes. Only the pieces that differ — attaching to a runtime the host owns, and
// posting to the host's JS thread — live under this module's own src/main.
val apiKotlinDir = rootProject.layout.projectDirectory.dir("api/src/main/kotlin")
val apiCppDir = rootProject.layout.projectDirectory.dir("api/src/main/cpp")

android {
  namespace = "expo.modules.v2.react"

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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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

  publishing {
    singleVariant("release")
  }
}

kotlin {
  jvmToolchain(17)
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

afterEvaluate {
  publishing {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        artifact(cppSourcesZip)
      }
    }
  }
}

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
