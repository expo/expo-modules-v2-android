package io.github.expo.modules.v2.compiler

import io.github.expo.modules.v2.compiler.fir.ExpoModulesV2FirRegistrar
import io.github.expo.modules.v2.compiler.ir.ExpoModulesV2IrGenerationExtension
import io.github.expo.modules.v2.compiler.ir.ModuleHintScanner
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

class ExpoModulesV2ComponentRegistrar : ExpoCompilerPluginRegistrar() {
  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    FirExtensionRegistrarAdapter.registerExtension(ExpoModulesV2FirRegistrar())

    val hints = ModuleHintScanner { configuration.jvmClasspathRoots }
    val packageName = configuration.get(ExpoModulesV2ConfigurationKeys.PACKAGE_NAME)
    val messages = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    IrGenerationExtension.registerExtension(ExpoModulesV2IrGenerationExtension(hints, packageName, messages))
  }
}
