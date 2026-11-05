package io.github.expo.modules.v2.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Wires typegen into a project: the compiler option on each compilation that describes the module,
 * and the scanner task that hands the report to `expo-modules generate-types`.
 */
internal class Typegen(private val project: Project, private val options: ExpoModulesV2TypegenOptions) {
  /** The compilations that write a report, by name, with the path of their compile task. */
  private val compilations = linkedMapOf<String, String>()

  fun registerScannerTask() {
    project.tasks.register(SCANNER_TASK, WriteTypegenScanner::class.java) { task ->
      task.group = "expo"
      task.description = "Writes the scanner that `expo-modules generate-types --scanner` runs to " +
        "read this module's JavaScript surface."

      val chosen = project.provider { chosenCompilation() }
      task.report.set(chosen.flatMap { reportFile(it) }.map { it.asFile.absolutePath })
      task.compileTask.set(chosen.map { compilations.getValue(it) })
      task.rootDir.set(project.rootDir.absolutePath)
      task.scanner.set(project.layout.buildDirectory.file("$OUTPUT_DIR/scanner"))
      task.dependsOn(task.compileTask)
    }
  }

  /** The compiler options for [compilation]: the report's path while typegen is on, else none. */
  fun optionsFor(compilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
    if (compilation.isTest()) {
      return project.provider { emptyList() }
    }
    val report = reportFile(compilation.name)
    val taskPath = compilation.compileTaskProvider.name.let { name ->
      if (project.path == ":") ":$name" else "${project.path}:$name"
    }
    compilations[compilation.name] = taskPath

    val enabled = options.enabled
    // Declared as an output, so a compilation restored from the build cache restores its report.
    compilation.compileTaskProvider.configure { task ->
      task.outputs.files(enabled.map { if (it) listOf(report) else emptyList() })
        .withPropertyName("expoModulesTypegenReport")
    }

    // The provider must not capture the Project: KGP serializes it into the compile task's inputs.
    // A file option's path is internal, so the report's location does not key the build cache.
    return enabled.zip(report) { on, file ->
      if (on) listOf(FilesSubpluginOption(OPTION, listOf(file.asFile))) else emptyList()
    }
  }

  private fun reportFile(compilationName: String): Provider<RegularFile> =
    project.layout.buildDirectory.file("$OUTPUT_DIR/$compilationName/exports.json")

  private fun chosenCompilation(): String {
    options.compilation.orNull?.let { name ->
      check(name in compilations) {
        "expoModulesV2.typegen.compilation is '$name', but ${project.path} has no such compilation " +
          "with typegen; it has ${compilations.keys.joinToString().ifEmpty { "none" }}"
      }
      return name
    }
    return DEFAULT_COMPILATIONS.firstOrNull { it in compilations }
      ?: compilations.keys.firstOrNull()
      ?: error("${project.path} has no Kotlin compilation that typegen describes")
  }

  /** Test compilations only describe test code: `test`, `debugUnitTest`, `debugAndroidTest`. */
  private fun KotlinCompilation<*>.isTest(): Boolean = name.endsWith("test", ignoreCase = true)

  companion object {
    const val SCANNER_TASK = "expoModulesTypegenScanner"

    /** The compiler plugin's `typegenOutput` option. */
    private const val OPTION = "typegenOutput"
    private const val OUTPUT_DIR = "generated/expo-modules-v2/typegen"
    private val DEFAULT_COMPILATIONS = listOf("release", "main")
  }
}

/**
 * Writes `scanner`: an executable that `expo-modules generate-types --scanner` runs as
 * `scanner scan-exports <package dir>` and reads a `scan-exports` report from. It builds the
 * compilation first, so the report is never stale - set `EXPO_MODULES_TYPEGEN_SKIP_BUILD=1` to
 * print the last one instead - and sends the build's output to stderr, leaving stdout to the report.
 */
@DisableCachingByDefault(because = "Writing a few lines is faster than a cache lookup")
abstract class WriteTypegenScanner : DefaultTask() {
  /** The absolute path of the report the compilation writes. */
  @get:Input
  abstract val report: Property<String>

  /** The path of the task that compiles it, such as `:my-module:compileReleaseKotlin`. */
  @get:Input
  abstract val compileTask: Property<String>

  /** The root of the build the task belongs to, where its Gradle wrapper is. */
  @get:Input
  abstract val rootDir: Property<String>

  @get:OutputFile
  abstract val scanner: RegularFileProperty

  @TaskAction
  fun write() {
    val file = scanner.get().asFile
    file.writeText(
      """
      |#!/bin/sh
      |# Generated by the Expo Modules v2 Gradle plugin, for ${compileTask.get()}.
      |# Prints a scan-exports report, as `expo-modules generate-types --scanner <this file>` expects.
      |set -e
      |if [ "${'$'}1" != "scan-exports" ]; then
      |  echo "usage: ${'$'}0 scan-exports [paths...]" >&2
      |  exit 64
      |fi
      |ROOT=${shellQuote(rootDir.get())}
      |REPORT=${shellQuote(report.get())}
      |if [ -z "${'$'}EXPO_MODULES_TYPEGEN_SKIP_BUILD" ] && [ -x "${'$'}ROOT/gradlew" ]; then
      |  (cd "${'$'}ROOT" && ./gradlew --quiet ${shellQuote(compileTask.get())}) >&2
      |fi
      |if [ ! -f "${'$'}REPORT" ]; then
      |  echo "No typegen report at ${'$'}REPORT. Turn typegen on with expoModulesV2 { typegen { enabled = true } } and build ${compileTask.get()}." >&2
      |  exit 1
      |fi
      |cat "${'$'}REPORT"
      |
      """.trimMargin(),
    )
    file.setExecutable(true)
  }

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
