package io.github.expo.modules.v2.compiler.services

import io.github.expo.modules.v2.compiler.ExpoModulesV2ConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import java.io.File

fun TestConfigurationBuilder.configureTypegen() {
  useConfigurators(::TypegenConfigurator)
}

/** Where the plugin writes the typegen report of [module] in this test. */
fun TestServices.typegenOutputOf(module: TestModule): File =
  temporaryDirectoryManager.getOrCreateTempDirectory("typegen").resolve("${module.name}.exports.json")

private class TypegenConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
    configuration.put(
      ExpoModulesV2ConfigurationKeys.TYPEGEN_OUTPUT,
      testServices.typegenOutputOf(module).path,
    )
  }
}
