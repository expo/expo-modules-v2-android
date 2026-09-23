import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildconfig)
  alias(libs.plugins.gradle.plugin)
  // Published for the same reason :compiler-plugin is — a consuming app resolves this plugin, it
  // does not build it.
  alias(libs.plugins.vanniktech.mavenPublish)
}

mavenPublishing {
  // Prefixed for the same reason :react is — see there. Only the jar is renamed: Gradle derives the
  // marker's coordinates from the plugin id, and the publish plugin leaves that publication alone.
  coordinates(artifactId = "expo-modules-v2-gradle-plugin")

  // `GradlePlugin(...)` publishes the jar and the `io.github.expo.modules.v2.gradle.plugin` marker,
  // which is what makes `id("io.github.expo.modules.v2") version "<v>"` resolve for a consumer. The
  // empty javadoc jar is there because Central's validator demands one.
  configure(GradlePlugin(JavadocJar.Empty(), sourcesJar = true))

  pom {
    description = "The Gradle plugin that registers the Expo Modules API v2 Kotlin compiler " +
      "plugin on a project's Kotlin compilations"
  }
}

sourceSets {
  main {
    java.setSrcDirs(listOf("src"))
    resources.setSrcDirs(listOf("resources"))
  }
  test {
    java.setSrcDirs(listOf("test"))
    resources.setSrcDirs(listOf("testResources"))
  }
}

dependencies {
  implementation(libs.kotlin.gradle.plugin.api)
  // compileOnly: KotlinCompile (for the incremental-compilation workaround) lives in the full KGP,
  // which every consumer that triggers applyToCompilation already has on its build classpath.
  compileOnly(libs.kotlin.gradle.plugin)
}

buildConfig {
  packageName("io.github.expo.modules.v2.gradle")

  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"io.github.expo.modules.v2.compiler\"")
  buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"$group\"")
  buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"expo-modules-v2-compiler-plugin\"")
  // The compiler plugin is published once per Kotlin release as `<expo-modules-v2>-<kotlin>`; the
  // Gradle plugin appends the consumer's Kotlin at configuration time, so only this half is baked in.
  buildConfigField("String", "EXPO_MODULES_V2_VERSION", "\"${libs.versions.expo.modules.v2.get()}\"")
}

gradlePlugin {
  plugins {
    create("expoModulesV2") {
      // Gradle publishes a plugin marker under the plugin id as its Maven group, whatever the
      // project's own group is. The id therefore has to sit inside the verified `io.github.expo`
      // namespace too, or Central rejects the marker and `id(...) version "<v>"` never resolves.
      id = "io.github.expo.modules.v2"
      displayName = "Expo Modules API v2 Gradle plugin"
      description = "Applies the Expo Modules v2 Kotlin compiler plugin (generated RecordCodecs)."
      implementationClass = "io.github.expo.modules.v2.gradle.ExpoModulesV2GradlePlugin"
    }
  }
}
