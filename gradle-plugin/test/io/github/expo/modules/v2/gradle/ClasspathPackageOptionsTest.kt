package io.github.expo.modules.v2.gradle

import org.jetbrains.kotlin.gradle.plugin.CompositeSubpluginOption
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClasspathPackageOptionsTest {
  private val root: File = Files.createTempDirectory("classpath-package-options").toFile().canonicalFile
  private val project = root.resolve("app").apply { mkdirs() }
  private val gradleUserHome = root.resolve("gradle-home").apply { mkdirs() }

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `escapes percent, comma and equals`() {
    // The same string ExpoModulesV2CommandLineProcessorTest reads back.
    assertEquals("a%3Db%2Cc%25", ClasspathPackageOptions.escapeOptionPart("a=b,c%"))
  }

  @Test
  fun `passes only the entries on the compilation's classpath`() {
    val debug = root.resolve("node_modules/expo-font/android/build/debug/classes.jar")
    val release = root.resolve("node_modules/expo-font/android/build/release/classes.jar")
    val packages = mapOf(debug.path to "expo-font", release.path to "expo-font")

    val options = ClasspathPackageOptions.of(packages, listOf(release), project, gradleUserHome)

    assertEquals(listOf("expo-font=${release.path}"), options.map { it.value })
  }

  @Test
  fun `matches entries by canonical path`() {
    val jar = root.resolve("libs/font.jar")
    val packages = mapOf(root.resolve("libs/../libs/font.jar").path to "expo-font")

    val options = ClasspathPackageOptions.of(packages, listOf(jar), project, gradleUserHome)

    assertEquals(1, options.size)
  }

  @Test
  fun `a classpath without any of the entries gets no options`() {
    val packages = mapOf(root.resolve("font.jar").path to "expo-font")

    assertEquals(emptyList(), ClasspathPackageOptions.of(packages, listOf(root.resolve("other.jar")), project, gradleUserHome))
  }

  @Test
  fun `the cache key holds relocatable paths and the compiler gets escaped absolute ones`() {
    val cached = gradleUserHome.resolve("caches/transforms/abc/font,1.jar")
    val local = root.resolve("node_modules/@expo/ui/classes.jar")
    val packages = mapOf(local.path to "@expo/ui", cached.path to "expo=font")

    val options = ClasspathPackageOptions.of(packages, listOf(local, cached), project, gradleUserHome)
      .map { it as CompositeSubpluginOption }

    // Sorted by the relocatable entry: `gradle-user-home:` before `root-project:`.
    assertEquals(
      listOf(
        "expo%3Dfont=" + ClasspathPackageOptions.escapeOptionPart(cached.path),
        "@expo/ui=" + ClasspathPackageOptions.escapeOptionPart(local.path),
      ),
      options.map { it.value },
    )
    assertEquals(
      listOf(
        listOf("package" to "expo=font", "entry" to "gradle-user-home:caches/transforms/abc/font,1.jar"),
        listOf("package" to "@expo/ui", "entry" to "root-project:../node_modules/@expo/ui/classes.jar"),
      ),
      options.map { option -> option.originalOptions.map { it.key to it.value } },
    )
  }
}
