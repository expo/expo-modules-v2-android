package expo.modules.v2.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Registers the Expo Modules v2 Kotlin compiler plugin — the one that turns an
 * `@expo.modules.v2.annotations.Record` class into a record with a generated `RecordCodec` — with
 * every Kotlin compilation of the project it is applied to.
 *
 * The plugin adds no dependency of its own: every module that declares records already depends on
 * `:api`, which is where the annotation and the generated code's supporting types live.
 */
@Suppress("unused") // Used via reflection.
class ExpoModulesV2GradlePlugin : KotlinCompilerPluginSupportPlugin {
  override fun apply(target: Project) {
    target.extensions.create("expoModulesV2", ExpoModulesV2GradleExtension::class.java)
  }

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

  override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

  override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
    groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
    artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
    version = BuildConfig.KOTLIN_PLUGIN_VERSION,
  )

  override fun applyToCompilation(
    kotlinCompilation: KotlinCompilation<*>,
  ): Provider<List<SubpluginOption>> {
    val project = kotlinCompilation.target.project

    // This plugin generates a nested *classifier*, which incremental compilation's caches cannot
    // track: a downstream file that only sees `@Record` never learns the codec changed.
    kotlinCompilation.compileTaskProvider.configure { task ->
      (task as? KotlinCompile)?.incremental = false
    }

    // No options yet. The lambda must not capture the Project — KGP serializes this provider into
    // the compile task's inputs, and a Project reference breaks the configuration cache.
    return project.provider { emptyList() }
  }
}
