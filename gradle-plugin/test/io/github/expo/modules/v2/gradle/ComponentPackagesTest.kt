package io.github.expo.modules.v2.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.CompositeSubpluginOption
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `expoModulesV2.componentPackages` against a real dependency resolution: the plugin has to find
 * the files of each named component on the compilation's own compile classpath.
 *
 * The dependencies are modules from a local Maven repository. ProjectBuilder cannot run tasks, and
 * a project dependency's files are produced by one, so Gradle refuses to read them here; project
 * dependencies are what a scratch Android build checks instead.
 */
class ComponentPackagesTest {
  private val dir: File = Files.createTempDirectory("component-packages").toFile().canonicalFile

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun `passes the files of each mapped module dependency, and nothing for the others`() {
    val repository = dir.resolve("repository")
    val font = publish(repository, "host.exp.exponent", "expo-font", "1.0.0")
    publish(repository, "host.exp.exponent", "unmapped", "1.0.0")

    val app = ProjectBuilder.builder().withName("app").withProjectDir(dir.resolve("app")).build()
    // The test build has no repositories to resolve the standard library from.
    app.extensions.extraProperties["kotlin.stdlib.default.dependency"] = "false"
    app.plugins.apply("org.jetbrains.kotlin.jvm")
    val plugin = app.plugins.apply(ExpoModulesV2GradlePlugin::class.java)
    app.repositories.maven { it.url = repository.toURI() }
    app.dependencies.add("implementation", "host.exp.exponent:expo-font:1.0.0")
    app.dependencies.add("implementation", "host.exp.exponent:unmapped:1.0.0")
    app.dependencies.add("implementation", app.files(dir.resolve("local.jar")))
    app.extensions.getByType(ExpoModulesV2GradleExtension::class.java).componentPackages.putAll(
      mapOf("host.exp.exponent:expo-font" to "expo-font", ":not-a-dependency" to "expo-other"),
    )

    val main = app.extensions.getByType(KotlinJvmProjectExtension::class.java).target.compilations.getByName("main")
    val provider = plugin.applyToCompilation(main)
    // The classpath comes from the compile task, so it is there once the task is configured.
    main.compileTaskProvider.get()
    val options = provider.get().map { it as CompositeSubpluginOption }

    val fontJar = main.compileDependencyFiles.files.single { it.name == font.name }
    assertEquals(listOf("expo-font=${ClasspathPackageOptions.escapeOptionPart(fontJar.path)}"), options.map { it.value })
    assertEquals(
      listOf("package" to "expo-font", "component" to "host.exp.exponent:expo-font"),
      options.single().originalOptions.filter { it.key != "entry" }.map { it.key to it.value },
    )
  }

  /** A jar and a POM in the layout of a Maven repository. */
  private fun publish(repository: File, group: String, name: String, version: String): File {
    val directory = repository.resolve("${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
    directory.resolve("$name-$version.pom").writeText(
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>$group</groupId>
        <artifactId>$name</artifactId>
        <version>$version</version>
      </project>
      """.trimIndent(),
    )
    return directory.resolve("$name-$version.jar").apply { writeBytes(EMPTY_JAR) }
  }

  private companion object {
    /** An empty zip archive: its end-of-central-directory record and nothing else. */
    val EMPTY_JAR = byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18)
  }
}
