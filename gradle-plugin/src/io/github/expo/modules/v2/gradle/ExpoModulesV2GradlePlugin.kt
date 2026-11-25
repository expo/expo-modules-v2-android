package io.github.expo.modules.v2.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.CompositeSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

/**
 * Registers the Expo Modules v2 Kotlin compiler plugin - the one that turns an
 * `@io.github.expo.modules.v2.Record` class into a record with a generated `RecordCodec` - with
 * every Kotlin compilation of the project it is applied to.
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

    // This plugin generates a nested *classifier*, which incremental compilation's caches cannot
    // track: a downstream file that only sees `@Record` never learns the codec changed.
    kotlinCompilation.compileTaskProvider.configure { task ->
      (task as? KotlinCompile)?.incremental = false
    }

    // Mapped from the extension's property, so it does not capture the Project - KGP serializes this
    // provider into the compile task's inputs, and a Project reference breaks the configuration cache.
    val extension = project.extensions.getByType(ExpoModulesV2GradleExtension::class.java)
    val rootDir = project.rootDir
    val gradleUserHome = project.gradle.gradleUserHomeDir
    return extension.classpathPackages.map { packages ->
      classpathPackageOptions(packages, rootDir, gradleUserHome)
    }
  }

  private companion object {
    // MUST stay in sync with `ExpoModulesV2CommandLineProcessor.CLASSPATH_PACKAGE_OPTION`.
    const val CLASSPATH_PACKAGE_OPTION = "classpathPackage"

    /**
     * One `classpathPackage` option for each entry. The compiler gets the absolute path, but the
     * compile task's cache key gets only the inner options: the package and the entry's path
     * relative to the Gradle user home or to the root project. So the task can come from the build
     * cache when the project or the Gradle user home is somewhere else. Sorted, so the key does not
     * depend on the map's order.
     */
    fun classpathPackageOptions(
      packages: Map<String, String>,
      rootDir: File,
      gradleUserHome: File,
    ): List<SubpluginOption> = packages
      .map { (entry, packageName) -> Triple(entry, packageName, relocatable(File(entry), rootDir, gradleUserHome)) }
      .sortedWith(compareBy({ it.third }, { it.second }))
      .map { (entry, packageName, relocatableEntry) ->
        CompositeSubpluginOption(
          key = CLASSPATH_PACKAGE_OPTION,
          value = escapeOptionPart(packageName) + "=" + escapeOptionPart(entry),
          originalOptions = listOf(
            SubpluginOption(key = "package", value = packageName),
            SubpluginOption(key = "entry", value = relocatableEntry),
          ),
        )
      }

    private fun relocatable(entry: File, rootDir: File, gradleUserHome: File): String = when {
      entry.startsWith(gradleUserHome) -> "gradle-user-home:" + entry.relativeTo(gradleUserHome).invariantSeparatorsPath
      // A module library usually sits outside the root project, for example in `../node_modules`.
      else -> runCatching { "root-project:" + entry.relativeTo(rootDir).invariantSeparatorsPath }
        .getOrDefault(entry.path)
    }

    /**
     * Escapes `%`, `,` and `=`: the compiler splits plugin options on `,`, and the option splits on
     * the first `=`. MUST stay in sync with the unescaping in `ExpoModulesV2CommandLineProcessor`.
     */
    fun escapeOptionPart(part: String): String =
      part.replace("%", "%25").replace(",", "%2C").replace("=", "%3D")
  }
}
