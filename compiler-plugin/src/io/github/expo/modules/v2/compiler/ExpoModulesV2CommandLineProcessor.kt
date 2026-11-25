package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import java.io.File

/** The package of one classpath entry, from a `classpathPackage` option. */
class ClasspathPackage(val packageName: String, val entry: File)

/** Where the plugin's options land in the [CompilerConfiguration]. */
object ExpoModulesV2ConfigurationKeys {
  /** The `classpathPackage` options, in the order they were given. */
  val CLASSPATH_PACKAGES = CompilerConfigurationKey<List<ClasspathPackage>>("Expo Modules v2 classpath packages")
}

@Suppress("unused")
class ExpoModulesV2CommandLineProcessor : CommandLineProcessor {
  override val pluginId: String
    get() = BuildConfig.KOTLIN_PLUGIN_ID

  override val pluginOptions: Collection<CliOption>
    get() = listOf(CLASSPATH_PACKAGE_OPTION)

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
  ) {
    when (option.optionName) {
      CLASSPATH_PACKAGE_OPTION.optionName -> {
        configuration.appendList(ExpoModulesV2ConfigurationKeys.CLASSPATH_PACKAGES, parseClasspathPackage(value))
      }

      else -> error("Unexpected config option: '${option.optionName}'")
    }
  }

  /** `<package>=<entry>`. A package name has no `=`, so the first one ends it; a path may have more. */
  private fun parseClasspathPackage(value: String): ClasspathPackage {
    val separator = value.indexOf('=')
    val packageName = value.substring(0, separator.coerceAtLeast(0))
    val entry = value.substring(separator + 1)
    if (separator <= 0 || packageName.isBlank() || entry.isBlank()) {
      throw CliOptionProcessingException(
        "Expo Modules v2: the `${CLASSPATH_PACKAGE_OPTION.optionName}` option '$value' is not " +
          "`<package>=<classpath entry>`. Give the package name, then `=`, then the path of a jar or " +
          "classes directory on the compile classpath, for example `expo-font=/path/to/classes.jar`.",
      )
    }
    return ClasspathPackage(packageName, File(entry))
  }

  companion object {
    // MUST stay in sync with the option `ExpoModulesV2GradlePlugin` passes.
    val CLASSPATH_PACKAGE_OPTION = CliOption(
      optionName = "classpathPackage",
      valueDescription = "<package>=<classpath entry>",
      description = "The package of the modules in one compile classpath entry, such as the npm " +
        "package `expo-font` of a jar. discoveredExpoPackages() groups the modules by it.",
      required = false,
      allowMultipleOccurrences = true,
    )
  }
}
