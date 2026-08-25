package expo.modules.v2.compiler.services

import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

/**
 * Puts `:api` — `@Record`, `Record`, `RecordCodec`, `TypeDescriptor`, the registry — on both the
 * compile and the run classpath of every test module. `:compiler-plugin`'s build passes the paths
 * in through the `recordsRuntime.classpath` system property.
 */
private val recordsRuntimeClasspath: List<File> =
  System.getProperty("recordsRuntime.classpath")
    ?.split(File.pathSeparator)
    ?.map(::File)
    ?: error("Unable to get a valid classpath from the 'recordsRuntime.classpath' property")

fun TestConfigurationBuilder.configureRecordsRuntime() {
  useConfigurators(::RecordsRuntimeConfigurator)
  useCustomRuntimeClasspathProviders(::RecordsRuntimeClasspathProvider)
}

private class RecordsRuntimeConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(
    configuration: CompilerConfiguration,
    module: TestModule,
  ) {
    configuration.addJvmClasspathRoots(recordsRuntimeClasspath)
  }
}

private class RecordsRuntimeClasspathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> = recordsRuntimeClasspath
}
