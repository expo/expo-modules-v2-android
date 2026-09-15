package io.github.expo.modules.v2.compiler

import io.github.expo.modules.v2.compiler.fir.ExpoModulesV2FirRegistrar
import io.github.expo.modules.v2.compiler.ir.ExpoModulesV2IrGenerationExtension
import io.github.expo.modules.v2.compiler.ir.ModuleHintScanner
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

class ExpoModulesV2ComponentRegistrar : CompilerPluginRegistrar() {
  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    FirExtensionRegistrarAdapter.registerExtension(ExpoModulesV2FirRegistrar())

    val hints = ModuleHintScanner { configuration.jvmClasspathRoots }
    IrGenerationExtension.registerExtension(ExpoModulesV2IrGenerationExtension(hints))
  }
}
