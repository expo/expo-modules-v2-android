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
  // `GradlePlugin(...)` publishes the jar and the `expo.modules.v2.gradle.plugin` marker, which is
  // what makes `id("expo.modules.v2") version "<v>"` resolve for a consumer. The empty javadoc jar
  // is there because Central's validator demands one.
  configure(GradlePlugin(JavadocJar.Empty(), sourcesJar = true))
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
  packageName("expo.modules.v2.gradle")

  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"expo.modules.v2.compiler\"")
  buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"$group\"")
  buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"compiler-plugin\"")
  buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"$version\"")
}

gradlePlugin {
  plugins {
    create("expoModulesV2") {
      id = "expo.modules.v2"
      displayName = "Expo Modules API v2 Gradle plugin"
      description = "Applies the Expo Modules v2 Kotlin compiler plugin (generated RecordCodecs)."
      implementationClass = "expo.modules.v2.gradle.ExpoModulesV2GradlePlugin"
    }
  }
}
