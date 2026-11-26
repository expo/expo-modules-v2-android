package io.github.expo.modules.v2.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Registers the Expo Modules v2 Kotlin compiler plugin - the one that turns an
 * `@io.github.expo.modules.v2.Record` class into a record with a generated `RecordCodec` and expands
 * `discoveredExpoModules()` and `discoveredExpoPackages()` - with every Kotlin compilation of the
 * project it is applied to, and passes each compilation the `expoModulesV2.classpathPackages` entries
 * on its own compile classpath.
 *
 * The plugin adds no dependency of its own: every module that declares records already depends on
 * `:api`, which is where the annotation and the generated code's supporting types live.
 */
@Suppress("unused") // Used via reflection.
class ExpoModulesV2GradlePlugin : KotlinCompilerPluginSupportPlugin {
  private lateinit var project: Project

  override fun apply(target: Project) {
    project = target
    target.extensions.create("expoModulesV2", ExpoModulesV2GradleExtension::class.java)
  }

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

  override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

  // A compiler plugin is bound to the exact compiler it was built against, so one compiler-plugin
  // artifact is published per supported Kotlin release, versioned `<compiler-plugin>-<kotlin>`.
  // Resolving by the project's own Kotlin version keeps the consumer's `plugins {}` block
  // Kotlin-agnostic; a Kotlin release with no build fails resolution with the missing coordinates.
  override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
    groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
    artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
    version = "${BuildConfig.KOTLIN_PLUGIN_VERSION}-${project.getKotlinPluginVersion()}",
  )

  override fun applyToCompilation(
    kotlinCompilation: KotlinCompilation<*>,
  ): Provider<List<SubpluginOption>> {
    val project = kotlinCompilation.target.project

    // The files kotlinc gets: the compile task's own libraries. Not `compileDependencyFiles` - on
    // Android that is the raw `<variant>CompileClasspath` configuration, which cannot be resolved on
    // its own (its project dependencies have several artifact variants), and AGP hands the task its
    // classes jars through `libraries` instead. Filled when the task is configured, so it only
    // carries the classpath's own build dependencies, not the task itself.
    val classpath = project.objects.fileCollection()

    kotlinCompilation.compileTaskProvider.configure { task ->
      // This plugin generates a nested *classifier*, which incremental compilation's caches cannot
      // track: a downstream file that only sees `@Record` never learns the codec changed.
      (task as? KotlinCompile)?.incremental = false
      (task as? KotlinCompile)?.let { classpath.from(it.libraries) }
    }

    // Mapped from the extension's property and the compilation's classpath, so it does not capture
    // the Project - KGP serializes this provider into the compile task's inputs, and a Project
    // reference breaks the configuration cache.
    val extension = project.extensions.getByType(ExpoModulesV2GradleExtension::class.java)
    val rootDir = project.rootDir
    val gradleUserHome = project.gradle.gradleUserHomeDir
    return extension.classpathPackages.zip(classpath.elements) { packages, elements ->
      ClasspathPackageOptions.of(packages, elements.map { it.asFile }, rootDir, gradleUserHome)
    }
  }
}
