package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object ExpoModulesV2ConfigurationKeys {
  /**
   * Where to write the compilation's JavaScript surface for typegen, or absent to write none. See
   * `typegen/ExportedSurface.kt` for what the file holds.
   */
  val TYPEGEN_OUTPUT: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("typegen output")
}

@Suppress("unused")
class ExpoModulesV2CommandLineProcessor : CommandLineProcessor {
  override val pluginId: String
    get() = BuildConfig.KOTLIN_PLUGIN_ID

  override val pluginOptions: Collection<CliOption>
    get() = listOf(TYPEGEN_OUTPUT_OPTION)

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
  ) {
    when (option.optionName) {
      TYPEGEN_OUTPUT_OPTION.optionName -> configuration.put(ExpoModulesV2ConfigurationKeys.TYPEGEN_OUTPUT, value)
      else -> error("Unexpected config option: '${option.optionName}'")
    }
  }

  companion object {
    val TYPEGEN_OUTPUT_OPTION = CliOption(
      optionName = "typegenOutput",
      valueDescription = "<path>",
      description = "Write the JavaScript surface of the compilation to this file, for typegen",
      required = false,
      allowMultipleOccurrences = false,
    )
  }
}
