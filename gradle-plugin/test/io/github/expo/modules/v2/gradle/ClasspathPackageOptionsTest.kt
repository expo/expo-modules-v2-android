package io.github.expo.modules.v2.gradle

import io.github.expo.modules.v2.gradle.ClasspathPackageOptions.Artifact
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
  fun `gives every file of a mapped component its package`() {
    val classes = root.resolve("node_modules/expo-font/android/build/classes.jar")
    val resources = root.resolve("node_modules/expo-font/android/build/res.jar")
    val other = root.resolve("other.jar")
    val artifacts = listOf(Artifact(":expo-font", classes), Artifact(":expo-font", resources), Artifact(":other", other))

    val options = of(mapOf(":expo-font" to "expo-font"), artifacts, classpath = listOf(classes, resources, other))

    assertEquals(listOf("expo-font=${classes.path}", "expo-font=${resources.path}"), options.map { it.value })
  }

  @Test
  fun `leaves out files that are not on the compiler's classpath`() {
    val classes = root.resolve("font/classes.jar")
    val aar = root.resolve("font/font.aar")

    val options = of(
      mapOf(":expo-font" to "expo-font"),
      listOf(Artifact(":expo-font", classes), Artifact(":expo-font", aar)),
      classpath = listOf(root.resolve("font/../font/classes.jar")),
    )

    assertEquals(listOf("expo-font=${classes.path}"), options.map { it.value })
  }

  @Test
  fun `ignores files without a component and components without a package`() {
    val local = root.resolve("local.jar")
    val other = root.resolve("other.jar")

    val options = of(
      mapOf(":expo-font" to "expo-font"),
      listOf(Artifact(null, local), Artifact("com.example:other", other)),
      classpath = listOf(local, other),
    )

    assertEquals(emptyList(), options)
  }

  @Test
  fun `the cache key holds the component and relocatable paths, the compiler escaped absolute ones`() {
    val cached = gradleUserHome.resolve("caches/transforms/abc/font,1.jar")
    val local = root.resolve("node_modules/@expo/ui/classes.jar")
    val artifacts = listOf(Artifact(":expo-ui", local), Artifact("host.exp.exponent:expo-font", cached))

    val options = of(
      mapOf(":expo-ui" to "@expo/ui", "host.exp.exponent:expo-font" to "expo=font"),
      artifacts,
      classpath = listOf(local, cached),
    ).map { it as CompositeSubpluginOption }

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
        listOf(
          "package" to "expo=font",
          "component" to "host.exp.exponent:expo-font",
          "entry" to "gradle-user-home:caches/transforms/abc/font,1.jar",
        ),
        listOf(
          "package" to "@expo/ui",
          "component" to ":expo-ui",
          "entry" to "root-project:../node_modules/@expo/ui/classes.jar",
        ),
      ),
      options.map { option -> option.originalOptions.map { it.key to it.value } },
    )
  }

  private fun of(packages: Map<String, String>, artifacts: List<Artifact>, classpath: List<File>) =
    ClasspathPackageOptions.of(packages, artifacts, classpath, project, gradleUserHome)
}
