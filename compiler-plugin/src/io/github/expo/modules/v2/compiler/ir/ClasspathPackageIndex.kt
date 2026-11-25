package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.ClasspathPackage
import java.io.File

/**
 * The `classpathPackage` options, looked up by classpath entry. Entries match by canonical path,
 * so two spellings of one entry, such as one through a symbolic link, still match.
 */
class ClasspathPackageIndex(private val packages: List<ClasspathPackage>) {
  private val packagesByEntry: Map<String, String> by lazy {
    packages.associate { it.entry.canonicalPath to it.packageName }
  }

  /** The package of [entry], or null when no option gives it one. */
  fun packageOf(entry: File): String? =
    if (packages.isEmpty()) null else packagesByEntry[entry.canonicalPath]

  /**
   * Fails when packages are given but none of their entries is in [classpath]. The build then most
   * likely gave the entries of another compilation, such as another build variant, and every module
   * would silently be in no package.
   */
  fun checkAnyEntryIsOn(classpath: List<File>) {
    if (packages.isEmpty()) {
      return
    }
    val canonicalClasspath = classpath.mapTo(HashSet()) { it.canonicalPath }
    if (packagesByEntry.keys.none { it in canonicalClasspath }) {
      error(
        "Expo Modules v2: none of the ${packagesByEntry.size} `classpathPackage` entries is on the " +
          "compile classpath of this compilation, so discoveredExpoPackages() would find no packages. " +
          "The build probably gives the entries of another compilation, such as another build " +
          "variant. Give the classpath entries of every compilation that calls " +
          "discoveredExpoPackages(). First entry: ${packages.first().entry}",
      )
    }
  }
}
