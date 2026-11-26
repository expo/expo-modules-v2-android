package io.github.expo.modules.v2.compiler.services

import io.github.expo.modules.v2.compiler.ExpoModulesV2CommandLineProcessor
import io.github.expo.modules.v2.compiler.ExpoModulesV2ComponentRegistrar
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun TestConfigurationBuilder.configurePlugin() {
  useConfigurators(::ExtensionRegistrarConfigurator)
}

private class ExtensionRegistrarConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  private val registrar = ExpoModulesV2ComponentRegistrar()
  private val options = ExpoModulesV2CommandLineProcessor()

  private val packageNameOption: AbstractCliOption = ExpoModulesV2CommandLineProcessor.PACKAGE_NAME_OPTION

  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    module
      .directives[ExpoTestDirectives.EXPO_PACKAGE_NAME]
      .singleOrNull()
      ?.let { packageName ->
        options.processOption(packageNameOption, packageName, configuration)
      }
    with(registrar) { registerExtensions(configuration) }
  }
}
