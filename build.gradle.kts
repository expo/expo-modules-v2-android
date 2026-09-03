import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  // Declared here, not only in :react, so AGP lands on the root build's classpath. Resolved
  // inside a subproject instead, it gets its own classloader scope and the Kotlin Gradle plugin —
  // which comes from this one — cannot see `com.android.build.gradle.api.BaseVariant`.
  alias(libs.plugins.android.library) apply false
  // AGP must sit on the same classpath as the publish plugin so it can detect the AGP version.
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
    // The Central Portal "bundle" is nothing but this build's staging directory, zipped verbatim, so
    // every file left in there is uploaded — and Central meters published file count per
    // organization. Gradle writes md5/sha1/sha256/sha512 for each published file *and* for each .asc
    // signature, while Central mandates only md5 and sha1 and never reads a signature's checksum.
    // That is 10 files per artifact where 4 suffice. Each publish task has written all of its own
    // files by the time its doLast runs, and the plugin zips the directory at the end of the build,
    // so pruning here covers everything with no ordering hazard.
    //
    // The publish plugin does this itself from 0.37.0 on (`mavenCentralChecksums` and
    // `mavenCentralExcludeSignatureChecksums`), which needs Gradle 9 — drop this once that lands.
    tasks.withType<PublishToMavenRepository>().configureEach {
      if (name.endsWith("ToMavenCentralRepository")) {
        // Lazy: the staging URL only exists once the plugin has created the deployment.
        val stagingUrl = provider { repository.url }
        doLast { pruneRedundantChecksums(stagingUrl.get()) }
      }
    }

    extensions.configure<MavenPublishBaseExtension> {
      publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

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

/**
 * Deletes the checksum files Maven Central does not need from [repositoryUrl]: `.sha256`/`.sha512`
 * for every file, plus every checksum of a `.asc` signature. Only touches `file:` repositories, so
 * a direct upload to a remote repository is left alone.
 */
fun pruneRedundantChecksums(repositoryUrl: java.net.URI) {
  if (repositoryUrl.scheme != "file") {
    return
  }

  val mandatory = setOf("md5", "sha1")
  val checksums = mandatory + setOf("sha256", "sha512")

  File(repositoryUrl).walkTopDown()
    .filter { it.isFile }
    .filter {
      val extension = it.extension
      when {
        extension !in checksums -> false
        // A signature needs no integrity file of its own - it already covers the artifact.
        it.nameWithoutExtension.endsWith(".asc") -> true
        else -> extension !in mandatory
      }
    }
    .toList()
    .forEach { it.delete() }
}
