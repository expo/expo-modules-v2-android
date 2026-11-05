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
 * and the task that builds one of them and writes the scanner `expo-modules generate-types` runs.
 *
 * A report describes a whole compilation, so it relies on incremental compilation staying off,
 * which `ExpoModulesV2GradlePlugin.applyToCompilation` does for every compilation.
 */
internal class Typegen(private val project: Project, private val options: ExpoModulesV2TypegenOptions) {
  /** A compilation that writes a report. */
  private class Described(val compilationName: String, val compileTask: String, val report: Provider<RegularFile>)

  /**
   * The compilations that write a report, by compile task: two targets of a multiplatform project
   * each have a `main` compilation, but never the same compile task.
   */
  private val compilations = linkedMapOf<String, Described>()

  fun registerTask() {
    project.tasks.register(TASK, WriteTypegenScanner::class.java) { task ->
      task.group = "expo"
      task.description = "Builds the JavaScript surface of this module for typegen, and writes the " +
        "scanner that `expo-modules generate-types --scanner` reads it through."

      val chosen = project.provider { chosenCompilation() }
      task.report.set(chosen.flatMap { it.report }.map { it.asFile.absolutePath })
      task.compileTask.set(chosen.map { it.compileTask })
      task.scanner.set(project.layout.buildDirectory.file("$OUTPUT_DIR/scanner"))
      task.dependsOn(task.compileTask)
    }
  }

  /** The compiler options for [compilation]: the report's path while typegen is on, else none. */
  fun optionsFor(compilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
    if (compilation.isTest()) {
      return project.provider { emptyList() }
    }
    val taskName = compilation.compileTaskProvider.name
    val report = project.layout.buildDirectory.file("$OUTPUT_DIR/$taskName/exports.json")
    val taskPath = if (project.path == ":") ":$taskName" else "${project.path}:$taskName"
    compilations[taskName] = Described(compilation.name, taskPath, report)

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

  private fun chosenCompilation(): Described {
    val described = compilations.values
    check(described.isNotEmpty()) { "${project.path} has no Kotlin compilation that typegen describes" }
    options.compilation.orNull?.let { name ->
      return described.firstOrNull { it.compilationName == name || it.compileTask.endsWith(":$name") }
        ?: error(
          "expoModulesV2.typegen.compilation is '$name', but ${project.path} has no such compilation; " +
            "typegen describes ${described.joinToString { it.compilationName }}",
        )
    }
    // What a developer builds already: debug before release, whatever the flavor.
    return DEFAULT_PREFERENCE.firstNotNullOfOrNull { matches -> described.firstOrNull { matches(it.compilationName) } }
      ?: described.first()
  }

  /** Test compilations only describe test code: `test`, `debugUnitTest`, `debugAndroidTest`. */
  private fun KotlinCompilation<*>.isTest(): Boolean = name.endsWith("test", ignoreCase = true)

  companion object {
    const val TASK = "expoModulesTypegen"

    /** The compiler plugin's `typegenOutput` option. */
    private const val OPTION = "typegenOutput"
    private const val OUTPUT_DIR = "generated/expo-modules-v2/typegen"

    private val DEFAULT_PREFERENCE: List<(String) -> Boolean> = listOf(
      { it == "debug" },
      { it.endsWith("Debug") },
      { it == "main" },
      { it == "release" },
      { it.endsWith("Release") },
    )
  }
}

/**
 * Writes `scanner`: an executable that `expo-modules generate-types --scanner` runs as
 * `scanner scan-exports <package dir>`, and that prints the report the compilation wrote. It does
 * not build anything itself: the task that writes it builds the compilation first, so run the task
 * again after the Kotlin changes.
 */
@DisableCachingByDefault(because = "Writing a few lines is faster than a cache lookup")
abstract class WriteTypegenScanner : DefaultTask() {
  /** The absolute path of the report the compilation writes. */
  @get:Input
  abstract val report: Property<String>

  /** The path of the task that compiles it, such as `:my-module:compileDebugKotlin`. */
  @get:Input
  abstract val compileTask: Property<String>

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
      |if [ "${'$'}1" != "scan-exports" ]; then
      |  echo "usage: ${'$'}0 scan-exports [paths...]" >&2
      |  exit 64
      |fi
      |REPORT=${shellQuote(report.get())}
      |if [ ! -f "${'$'}REPORT" ]; then
      |  echo "No typegen report at ${'$'}REPORT. Turn typegen on with expoModulesV2 { typegen { enabled = true } } and run ${shellQuote(taskPath())}." >&2
      |  exit 1
      |fi
      |exec cat "${'$'}REPORT"
      |
      """.trimMargin(),
    )
    file.setExecutable(true)
  }

  /** This task's path, which the build that produces the report runs. */
  private fun taskPath(): String = path

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
