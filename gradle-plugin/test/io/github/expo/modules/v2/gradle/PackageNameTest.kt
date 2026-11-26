package io.github.expo.modules.v2.gradle

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `expoModulesV2.packageName`, and the `packageName` option each compilation gets from it. */
class PackageNameTest {
  private val dir: File = Files.createTempDirectory("package-name").toFile().canonicalFile

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun `defaults to the name in the package's package json`() {
    val android = dir.resolve("node_modules/@expo/ui/android")
    android.resolveSibling("package.json").apply { parentFile.mkdirs() }.writeText(
      """
      {
        "name": "@expo/ui",
        "version": "1.0.0",
        "dependencies": { "nested": { "name": "not-this-one" } }
      }
      """.trimIndent(),
    )
    val (project, plugin) = library(android)

    assertEquals("@expo/ui", project.expoModulesV2.packageName.get())
    assertEquals(listOf("packageName" to "@expo/ui"), plugin.options(project))
  }

  @Test
  fun `an explicit name wins over package json`() {
    val android = dir.resolve("node_modules/expo-font/android")
    android.resolveSibling("package.json").apply { parentFile.mkdirs() }.writeText("""{ "name": "expo-font" }""")
    val (project, plugin) = library(android)
    project.expoModulesV2.packageName.set("expo-font-next")

    assertEquals(listOf("packageName" to "expo-font-next"), plugin.options(project))
  }

  @Test
  fun `no package json means no package and no option`() {
    val (project, plugin) = library(dir.resolve("app"))

    assertEquals(false, project.expoModulesV2.packageName.isPresent)
    assertEquals(emptyList(), plugin.options(project))
  }

  private fun library(projectDir: File): Pair<Project, ExpoModulesV2GradlePlugin> {
    projectDir.mkdirs()
    val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
    project.plugins.apply("org.jetbrains.kotlin.jvm")
    return project to project.plugins.apply(ExpoModulesV2GradlePlugin::class.java)
  }

  private val Project.expoModulesV2: ExpoModulesV2GradleExtension
    get() = extensions.getByType(ExpoModulesV2GradleExtension::class.java)

  private fun ExpoModulesV2GradlePlugin.options(project: Project): List<Pair<String, String>> {
    val main = project.extensions.getByType(KotlinJvmProjectExtension::class.java).target.compilations.getByName("main")
    return applyToCompilation(main).get().map { it.key to it.value }
  }
}
