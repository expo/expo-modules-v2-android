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
import org.jetbrains.kotlin.test.services.jvm.compiledClassesManager

fun TestConfigurationBuilder.configurePlugin() {
  useConfigurators(::ExtensionRegistrarConfigurator)
}

private class ExtensionRegistrarConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  private val registrar = ExpoModulesV2ComponentRegistrar()
  private val options = ExpoModulesV2CommandLineProcessor()

  // Typed as the base class, so the call picks the `processOption` the plugin implements: the
  // overload that takes a `CliOption` is deprecated and does nothing.
  private val classpathPackageOption: AbstractCliOption = ExpoModulesV2CommandLineProcessor.CLASSPATH_PACKAGE_OPTION

  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    // Each dependency with a package puts its output on this module's classpath under that package,
    // through the command line processor, the way `-P plugin:...:classpathPackage=` reaches it.
    for (dependency in module.allDependencies.map { it.dependencyModule }) {
      val packageName = dependency.directives[ExpoTestDirectives.EXPO_PACKAGE_NAME].singleOrNull()
        ?: continue
      val output = testServices.compiledClassesManager.getOutputDirForModule(dependency)
      options.processOption(classpathPackageOption, "${escape(packageName)}=${escape(output.path)}", configuration)
    }
    with(registrar) { registerExtensions(configuration) }
  }

  /**
   * What the Gradle plugin's `ClasspathPackageOptions.escapeOptionPart` does to each part: the
   * compiler splits plugin options on `,`. Both sides' unit tests pin the same escaped string.
   */
  private fun escape(part: String): String =
    part.replace("%", "%25").replace(",", "%2C").replace("=", "%3D")
}
