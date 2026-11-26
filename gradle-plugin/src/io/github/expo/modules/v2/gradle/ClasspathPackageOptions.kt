package io.github.expo.modules.v2.gradle

import org.jetbrains.kotlin.gradle.plugin.CompositeSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

/** Turns `expoModulesV2.classpathPackages` into the compiler plugin's `classpathPackage` options. */
internal object ClasspathPackageOptions {
  // MUST stay in sync with `ExpoModulesV2CommandLineProcessor.CLASSPATH_PACKAGE_OPTION`.
  const val CLASSPATH_PACKAGE_OPTION = "classpathPackage"

  /**
   * One `classpathPackage` option for each entry of [packages] that is on [classpath], the compile
   * classpath of one compilation. Entries match by canonical path, as in the compiler. Leaving out
   * the entries of other compilations, such as other build variants, keeps a compilation from
   * seeing packages for entries it does not have, and keeps their paths out of its cache key.
   *
   * The compiler gets the absolute path, but the compile task's cache key gets only the inner
   * options: the package and the entry's path relative to the Gradle user home or to the root
   * project. So the task can come from the build cache when the project or the Gradle user home is
   * somewhere else. Sorted, so the key does not depend on the map's order.
   */
  fun of(
    packages: Map<String, String>,
    classpath: Iterable<File>,
    rootDir: File,
    gradleUserHome: File,
  ): List<SubpluginOption> {
    if (packages.isEmpty()) {
      return emptyList()
    }
    val canonicalClasspath = classpath.mapTo(HashSet()) { it.canonicalPath }
    return packages
      .filterKeys { entry -> File(entry).canonicalPath in canonicalClasspath }
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
  }

  /**
   * Escapes `%`, `,` and `=`: the compiler splits plugin options on `,`, and the option splits on
   * the first `=`. MUST stay in sync with the unescaping in `ExpoModulesV2CommandLineProcessor`;
   * both sides' tests pin the same string.
   */
  fun escapeOptionPart(part: String): String =
    part.replace("%", "%25").replace(",", "%2C").replace("=", "%3D")

  private fun relocatable(entry: File, rootDir: File, gradleUserHome: File): String = when {
    entry.startsWith(gradleUserHome) -> "gradle-user-home:" + entry.relativeTo(gradleUserHome).invariantSeparatorsPath
    // A module library usually sits outside the root project, for example in `../node_modules`.
    else -> runCatching { "root-project:" + entry.relativeTo(rootDir).invariantSeparatorsPath }
      .getOrDefault(entry.path)
  }
}
