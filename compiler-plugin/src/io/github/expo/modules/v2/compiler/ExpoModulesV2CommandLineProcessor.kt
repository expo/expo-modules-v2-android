package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object ExpoModulesV2ConfigurationKeys {
  val PACKAGE_NAME = CompilerConfigurationKey<String>("Expo Modules v2 package name")
}

@Suppress("unused")
class ExpoModulesV2CommandLineProcessor : CommandLineProcessor {
  override val pluginId: String
    get() = BuildConfig.KOTLIN_PLUGIN_ID

  override val pluginOptions: Collection<CliOption>
    get() = listOf(PACKAGE_NAME_OPTION)

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
  ) {
    when (option.optionName) {
      PACKAGE_NAME_OPTION.optionName -> {
        configuration.put(ExpoModulesV2ConfigurationKeys.PACKAGE_NAME, parsePackageName(value))
      }

      else -> error("Unexpected config option: '${option.optionName}'")
    }
  }

  internal fun parsePackageName(value: String): String {
    if (value.isBlank() || ',' in value) {
      throw CliOptionProcessingException(
        "Expo Modules v2: the `${PACKAGE_NAME_OPTION.optionName}` option '$value' is not a package " +
          "name. Give the npm package of the library, for example `expo-font` or `@expo/ui`.",
      )
    }
    return value
  }

  companion object {
    val PACKAGE_NAME_OPTION = CliOption(
      optionName = "packageName",
      valueDescription = "<package>",
      description = "The package of the modules this compilation declares, such as the npm package " +
        "`expo-font`. It is recorded on their module hints, and discoveredExpoPackages() in a " +
        "compilation that finds them groups them by it.",
      required = false,
      allowMultipleOccurrences = false,
    )
  }
}
