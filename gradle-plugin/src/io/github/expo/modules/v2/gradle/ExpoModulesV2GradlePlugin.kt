package io.github.expo.modules.v2.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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

    kotlinCompilation
      .compileTaskProvider
      .configure { task ->
        // This plugin generates a nested *classifier*, which incremental compilation's caches cannot
        // track: a downstream file that only sees `@Record` never learns the codec changed.
        (task as? KotlinCompile)?.incremental = false
      }

    // Mapped from the extension's property, so it does not capture the Project - KGP serializes this
    // provider into the compile task's inputs, and a Project reference breaks the configuration cache.
    val extension = project
      .extensions
      .getByType(ExpoModulesV2GradleExtension::class.java)

    return extension
      .packageName
      .map { packageName -> listOf(SubpluginOption(key = PACKAGE_NAME_OPTION, value = packageName)) }
      .orElse(emptyList())
  }

  private companion object {
    // MUST stay in sync with `ExpoModulesV2CommandLineProcessor.PACKAGE_NAME_OPTION`.
    const val PACKAGE_NAME_OPTION = "packageName"
  }
}
