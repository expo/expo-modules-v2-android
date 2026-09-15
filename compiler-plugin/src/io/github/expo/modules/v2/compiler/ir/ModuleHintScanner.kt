package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Lists the hint interfaces on a compile classpath: every top-level class in
 * `io.github.expo.modules.v2.hints`, whether it sits in a jar or in a classes directory.
 *
 * The compiler cannot enumerate a package for us from the backend, so this reads the class files
 * directly, the way Hilt's aggregation task or Anvil's hint lookup do. It only looks at entry names,
 * never at bytecode: which module a hint names is read afterwards through the compiler, from the
 * hint's one member. [roots] is read lazily, at the first call that needs the result, so a
 * compilation that never asks for `discoveredExpoModules()` never pays for the scan.
 *
 * What the scan costs is opening archives, which reads each one's table of contents, and walking
 * the names in it. Two things keep that short:
 *  - jars that belong to the toolchain rather than to a module - the Android platform stubs, the
 *    Kotlin runtime, React Native - are recognized by name and never opened;
 *  - a jar that records directory entries is settled by one lookup of the hints directory. Only a
 *    jar without directory entries, or one where the lookup hits, has its names walked.
 */
class ModuleHintScanner(private val roots: () -> List<File>) {
  val hints: List<ClassId> by lazy { scan() }

  private fun scan(): List<ClassId> {
    val prefix = Identifiers.Hints.PACKAGE.asString().replace('.', '/') + "/"
    val names = sortedSetOf<String>()

    for (root in roots()) {
      when {
        root.isToolchainJar() -> continue

        root.isDirectory -> File(root, prefix)
          .listFiles()
          ?.forEach { file -> hintNameOf(file.name)?.let(names::add) }

        root.isFile -> forEachHintEntry(root, prefix) { entry ->
          hintNameOf(entry.removePrefix(prefix))?.let(names::add)
        }
      }
    }

    return names.map { ClassId(Identifiers.Hints.PACKAGE, Name.identifier(it)) }
  }

  /**
   * Whether this jar comes from the toolchain and so cannot contain a module: the Android platform
   * stubs (`<sdk>/platforms/android-<v>/android.jar`, `<sdk>/build-tools/<v>/core-lambda-stubs.jar`),
   * the Kotlin runtime, and React Native's own jars. Matched on the file name, with the version
   * suffix, so a module that merely starts with `kotlin-` or `react-` is still scanned.
   */
  private fun File.isToolchainJar(): Boolean {
    if (name == ANDROID_PLATFORM_JAR) {
      val platform = parentFile
        ?: return false

      return platform.name.startsWith(ANDROID_PLATFORM_PREFIX) && platform.parentFile?.name == ANDROID_PLATFORMS_DIR
    }
    if (name == ANDROID_LAMBDA_STUBS_JAR) {
      return parentFile?.parentFile?.name == ANDROID_BUILD_TOOLS_DIR
    }
    return TOOLCHAIN_ARTIFACTS.any { artifact -> name.startsWith("$artifact-") && name.hasVersionAfter(artifact) }
  }

  /** `kotlin-stdlib-2.2.0.jar` matches `kotlin-stdlib`; `kotlin-stdlib-jdk8-2.2.0.jar` matches too. */
  private fun String.hasVersionAfter(artifact: String): Boolean {
    val rest = removePrefix("$artifact-").removeSuffix(".jar")
    return rest.split('-').any { part -> part.isNotEmpty() && part[0].isDigit() }
  }

  /** The simple class name behind a `Foo.class` entry relative to the hints package, else null. */
  private fun hintNameOf(relativePath: String): String? {
    if (!relativePath.endsWith(CLASS_SUFFIX) || '/' in relativePath || '$' in relativePath) {
      return null
    }
    return relativePath.removeSuffix(CLASS_SUFFIX)
  }

  /** Calls [action] with every entry name under [prefix], opening the archive as cheaply as it can. */
  private inline fun forEachHintEntry(file: File, prefix: String, action: (String) -> Unit) {
    val zip = try {
      ZipFile(file)
    } catch (_: IOException) {
      // Not an archive (or unreadable): the compiler would have complained already if it mattered.
      return
    }
    zip.use {
      // A jar written with directory entries has one for every directory it holds. If its first
      // entry is a directory, the whole archive follows that convention, and a missing hints
      // directory means there is nothing to find. A jar without directory entries has to be walked.
      val entries = it.entries()
      if (!entries.hasMoreElements()) {
        return
      }
      val first = entries.nextElement().name
      if (first.endsWith("/") && it.getEntry(prefix) == null) {
        return
      }

      if (first.startsWith(prefix)) {
        action(first)
      }
      while (entries.hasMoreElements()) {
        val name = entries.nextElement().name
        if (name.startsWith(prefix)) {
          action(name)
        }
      }
    }
  }

  private companion object {
    const val CLASS_SUFFIX = ".class"

    const val ANDROID_PLATFORM_JAR = "android.jar"
    const val ANDROID_PLATFORM_PREFIX = "android-"
    const val ANDROID_PLATFORMS_DIR = "platforms"
    const val ANDROID_LAMBDA_STUBS_JAR = "core-lambda-stubs.jar"
    const val ANDROID_BUILD_TOOLS_DIR = "build-tools"

    /** Artifact ids, as they start a jar's file name, of libraries no Expo module is compiled into. */
    val TOOLCHAIN_ARTIFACTS = listOf(
      "kotlin-stdlib",
      "kotlin-reflect",
      "kotlinx-coroutines",
      "react-android",
      "hermes-android",
    )
  }
}
