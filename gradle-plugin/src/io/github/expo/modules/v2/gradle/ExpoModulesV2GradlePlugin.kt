package io.github.expo.modules.v2.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Registers the Expo Modules v2 Kotlin compiler plugin - the one that turns an
 * `@io.github.expo.modules.v2.Record` class into a record with a generated `RecordCodec` and expands
 * `discoveredExpoModules()` and `discoveredExpoPackages()` - with every Kotlin compilation of the
 * project it is applied to, and passes each compilation the package of each classpath file whose
 * component has one in `expoModulesV2.componentPackages`.
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

    // Mapped from the extension's property, the resolved artifacts and the classpath, so it does not
    // capture the Project - KGP serializes this provider into the compile task's inputs, and a
    // Project reference breaks the configuration cache.
    val extension = project.extensions.getByType(ExpoModulesV2GradleExtension::class.java)
    val artifacts = resolvedArtifacts(project, kotlinCompilation)
    val rootDir = project.rootDir
    val gradleUserHome = project.gradle.gradleUserHomeDir
    return extension.componentPackages
      .zip(artifacts) { packages, resolved -> packages to resolved }
      .zip(classpath.elements) { (packages, resolved), elements ->
        ClasspathPackageOptions.of(
          packages = packages,
          artifacts = resolved,
          classpath = elements.map { it.asFile },
          rootDir = rootDir,
          gradleUserHome = gradleUserHome,
        )
      }
  }

  /**
   * The compilation's compile classpath, resolved with the component each file belongs to. An
   * Android compilation asks for its libraries' classes jars, which is what AGP hands kotlinc; a JVM
   * compilation takes its configuration's own artifacts. Only files that are also in the task's
   * libraries become options, so a file this view picks differently is left out rather than wrong.
   * Lenient, because the compile task reports resolution failures itself.
   */
  private fun resolvedArtifacts(
    project: Project,
    kotlinCompilation: KotlinCompilation<*>,
  ): Provider<List<ClasspathPackageOptions.Artifact>> {
    val configuration = project.configurations.findByName(kotlinCompilation.compileDependencyConfigurationName)
      ?: return project.provider { emptyList() }
    val isAndroid = kotlinCompilation.platformType == KotlinPlatformType.androidJvm
    return configuration.incoming
      .artifactView { view ->
        view.lenient(true)
        if (isAndroid) {
          view.attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ANDROID_CLASSES_JAR)
        }
      }
      .artifacts
      .resolvedArtifacts
      // Plain values right away: the configuration cache may store this provider's value, and it
      // cannot serialize a `ResolvedArtifactResult`.
      .map { resolved -> resolved.map(::artifactOf) }
  }

  private companion object {
    fun artifactOf(result: ResolvedArtifactResult) = ClasspathPackageOptions.Artifact(
      component = ClasspathPackageOptions.componentKey(result.id.componentIdentifier),
      file = result.file,
    )

    /** AGP's artifact type for a library's compiled classes, `AndroidArtifacts.ArtifactType.CLASSES_JAR`. */
    const val ANDROID_CLASSES_JAR = "android-classes-jar"
  }
}
