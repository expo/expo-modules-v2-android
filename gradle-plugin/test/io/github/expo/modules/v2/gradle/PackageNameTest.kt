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
  fun `passes the package name to every compilation, also a scoped one`() {
    val (project, plugin) = library(dir.resolve("ui"))
    project.expoModulesV2.packageName.set("@expo/ui")

    assertEquals(listOf("packageName" to "@expo/ui"), plugin.options(project, "main"))
    assertEquals(listOf("packageName" to "@expo/ui"), plugin.options(project, "test"))
  }

  @Test
  fun `passes nothing when no package name is set, whatever sits next to the project`() {
    // The Expo layout, `node_modules/<package>/android`: the plugin does not read package.json.
    val android = dir.resolve("node_modules/expo-font/android")
    android.resolveSibling("package.json").apply { parentFile.mkdirs() }.writeText("""{ "name": "expo-font" }""")
    val (project, plugin) = library(android)

    assertEquals(false, project.expoModulesV2.packageName.isPresent)
    assertEquals(emptyList(), plugin.options(project, "main"))
  }

  @Test
  fun `another build script can set it, the way autolinking would`() {
    val root = ProjectBuilder.builder().withProjectDir(dir).build()
    val (library, plugin) = library(dir.resolve("font"), parent = root)

    // What a settings or root-project plugin does once it knows the library's package.
    root.subprojects { project ->
      project.plugins.withType(ExpoModulesV2GradlePlugin::class.java) {
        project.extensions.getByType(ExpoModulesV2GradleExtension::class.java).packageName.set("expo-font")
      }
    }

    assertEquals(listOf("packageName" to "expo-font"), plugin.options(library, "main"))
  }

  private fun library(projectDir: File, parent: Project? = null): Pair<Project, ExpoModulesV2GradlePlugin> {
    projectDir.mkdirs()
    val project = ProjectBuilder.builder().withProjectDir(projectDir).withParent(parent).withName(projectDir.name).build()
    project.plugins.apply("org.jetbrains.kotlin.jvm")
    return project to project.plugins.apply(ExpoModulesV2GradlePlugin::class.java)
  }

  private val Project.expoModulesV2: ExpoModulesV2GradleExtension
    get() = extensions.getByType(ExpoModulesV2GradleExtension::class.java)

  private fun ExpoModulesV2GradlePlugin.options(project: Project, compilation: String): List<Pair<String, String>> {
    val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
    return applyToCompilation(kotlin.target.compilations.getByName(compilation)).get().map { it.key to it.value }
  }
}
