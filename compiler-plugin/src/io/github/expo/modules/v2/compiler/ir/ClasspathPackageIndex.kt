package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.ClasspathPackage
import java.io.File

/**
 * The `classpathPackage` options, looked up by classpath entry. Entries match by canonical path,
 * so two spellings of one entry, such as one through a symbolic link, still match.
 *
 * An option whose entry is not on the compile classpath is ignored: the Gradle plugin only passes the
 * entries of the compilation's own classpath, and a compilation without any is fine too - its
 * `discoveredExpoPackages()` is then empty.
 */
class ClasspathPackageIndex(private val packages: List<ClasspathPackage>) {
  /** The package of each canonical entry. The first option wins; see [conflicts]. */
  private val packagesByEntry: Map<String, String> by lazy {
    val byEntry = LinkedHashMap<String, String>()
    for (option in packages) {
      byEntry.putIfAbsent(option.entry.canonicalPath, option.packageName)
    }
    byEntry
  }

  /** The canonical path of each root [packageOf] was asked about, so each is resolved once. */
  private val canonicalRoots = HashMap<File, String>()

  /** Whether any option was given. */
  val isEmpty: Boolean
    get() = packages.isEmpty()

  /**
   * One message for each entry that options give different packages - typically two spellings of
   * one entry, such as a symbolic link and its target. The first option's package is used.
   */
  val conflicts: List<String> by lazy {
    packages
      .groupBy { it.entry.canonicalPath }
      .filterValues { options -> options.map { it.packageName }.distinct().size > 1 }
      .map { (entry, options) ->
        "Expo Modules v2: the `classpathPackage` options give the classpath entry $entry more than " +
          "one package: ${options.joinToString { "'${it.packageName}' (as ${it.entry})" }}. Give each " +
          "entry one package."
      }
  }

  /** The package of [entry], or null when no option gives it one. */
  fun packageOf(entry: File): String? {
    if (packages.isEmpty()) {
      return null
    }
    val canonical = canonicalRoots.getOrPut(entry) { entry.canonicalPath }
    return packagesByEntry[canonical]
  }
}
