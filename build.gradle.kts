import com.vanniktech.maven.publish.Checksum
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  // Declared here, not only in :react, so AGP lands on the root build's classpath: it must share a
  // classloader with the Kotlin Gradle plugin (its built-in Kotlin drives KGP's compile task) and
  // with the publish plugin, which detects the AGP version.
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.vanniktech.mavenPublish) apply false
  base
}

val expoModulesV2Version: String = libs.versions.expo.modules.v2.get()

allprojects {
  group = "io.github.expo"
  version = expoModulesV2Version
}

subprojects {
  configureJavaCompatibility()
  configureCentralPublishing()
}

// One command for the two artifacts a consumer resolves rather than builds.
val publishToolchainToMavenLocal by tasks.registering {
  group = "publishing"
  description = "Publishes :compiler-plugin and :gradle-plugin to the local Maven repository."
  dependsOn(":compiler-plugin:publishToMavenLocal", ":gradle-plugin:publishToMavenLocal")
}

val publishToolchainToMavenCentral by tasks.registering {
  group = "publishing"
  description = "Publishes :compiler-plugin and :gradle-plugin to Maven Central."
  dependsOn(":compiler-plugin:publishToMavenCentral", ":gradle-plugin:publishToMavenCentral")
}

fun Project.configureJavaCompatibility() {
  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
      jvmToolchain(17)
      compilerOptions {
        jvmTarget = JvmTarget.JVM_11
      }
    }
    tasks.withType<JavaCompile>().configureEach {
      options.release = 11
    }
  }
}

fun Project.configureCentralPublishing() {
  plugins.withId("com.vanniktech.maven.publish") {
    extensions.configure<MavenPublishBaseExtension> {
      publishToMavenCentral(automaticRelease = true)

      // Only sign when signing credentials are available (CI environment).
      if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
      }

      pom {
        name = project.name
        inceptionYear = "2026"
        url = "https://github.com/expo/expo-modules-android-v2"
        licenses {
          license {
            name = "The MIT License"
            url = "https://opensource.org/license/mit"
            distribution = "https://opensource.org/license/mit"
          }
        }
        developers {
          developer {
            id = "expo"
            name = "Expo"
            url = "https://github.com/expo"
          }
        }
        scm {
          url = "https://github.com/expo/expo-modules-android-v2"
          connection = "scm:git:git://github.com/expo/expo-modules-android-v2.git"
          developerConnection = "scm:git:ssh://github.com/expo/expo-modules-android-v2.git"
        }
      }
    }
  }
}
