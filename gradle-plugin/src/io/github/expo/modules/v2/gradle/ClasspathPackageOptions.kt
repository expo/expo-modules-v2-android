package io.github.expo.modules.v2.gradle

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.jetbrains.kotlin.gradle.plugin.CompositeSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

/** Turns `expoModulesV2.componentPackages` into the compiler plugin's `classpathPackage` options. */
internal object ClasspathPackageOptions {
  // MUST stay in sync with `ExpoModulesV2CommandLineProcessor.CLASSPATH_PACKAGE_OPTION`.
  const val CLASSPATH_PACKAGE_OPTION = "classpathPackage"

  /** One file of a compilation's resolved compile classpath, and the component it belongs to. */
  data class Artifact(val component: String?, val file: File) : java.io.Serializable

  /**
   * The key [componentKey] gives a component in `expoModulesV2.componentPackages`: a project
   * dependency's path in the build tree, such as `:expo-font`, or a module dependency's
   * `group:name`, without its version. Null for anything else, such as a local file dependency.
   */
  fun componentKey(id: ComponentIdentifier): String? = when (id) {
    is ProjectComponentIdentifier -> id.buildTreePath
    is ModuleComponentIdentifier -> "${id.group}:${id.module}"
    else -> null
  }

  /**
   * One `classpathPackage` option for each file in [artifacts] whose component has a package in
   * [packages] and that is on [classpath], the files the compiler gets. Files match by canonical
   * path, as in the compiler. The compilation's own resolved artifacts are what tie a component to
   * its files, so each build variant gets its own files without anyone listing them.
   *
   * The compiler gets the absolute path, but the compile task's cache key gets only the inner
   * options: the package, the component, and the file's path relative to the Gradle user home or to
   * the root project. So the task can come from the build cache when the project or the Gradle user
   * home is somewhere else. Sorted, so the key does not depend on resolution order.
   */
  fun of(
    packages: Map<String, String>,
    artifacts: Iterable<Artifact>,
    classpath: Iterable<File>,
    rootDir: File,
    gradleUserHome: File,
  ): List<SubpluginOption> {
    if (packages.isEmpty()) {
      return emptyList()
    }
    val canonicalClasspath = classpath.mapTo(HashSet()) { it.canonicalPath }
    return artifacts
      .mapNotNull { artifact ->
        val packageName = artifact.component?.let(packages::get) ?: return@mapNotNull null
        if (artifact.file.canonicalPath !in canonicalClasspath) {
          return@mapNotNull null
        }
        Entry(artifact.file.path, packageName, artifact.component, relocatable(artifact.file, rootDir, gradleUserHome))
      }
      .distinctBy { it.path }
      .sortedWith(compareBy({ it.relocatablePath }, { it.packageName }))
      .map { entry ->
        CompositeSubpluginOption(
          key = CLASSPATH_PACKAGE_OPTION,
          value = escapeOptionPart(entry.packageName) + "=" + escapeOptionPart(entry.path),
          originalOptions = listOf(
            SubpluginOption(key = "package", value = entry.packageName),
            SubpluginOption(key = "component", value = entry.component),
            SubpluginOption(key = "entry", value = entry.relocatablePath),
          ),
        )
      }
  }

  /**
   * Escapes `%`, `,` and `=`: the compiler splits plugin options on `,`, and the option splits on
   * the first `=`. MUST stay in sync with the unescaping in `ExpoModulesV2CommandLineProcessor`;
   * both sides' tests pin the same string.
   */
  fun escapeOptionPart(part: String): String =
    part.replace("%", "%25").replace(",", "%2C").replace("=", "%3D")

  private class Entry(val path: String, val packageName: String, val component: String, val relocatablePath: String)

  private fun relocatable(entry: File, rootDir: File, gradleUserHome: File): String = when {
    entry.startsWith(gradleUserHome) -> "gradle-user-home:" + entry.relativeTo(gradleUserHome).invariantSeparatorsPath
    // A module library usually sits outside the root project, for example in `../node_modules`.
    else -> runCatching { "root-project:" + entry.relativeTo(rootDir).invariantSeparatorsPath }
      .getOrDefault(entry.path)
  }
}
