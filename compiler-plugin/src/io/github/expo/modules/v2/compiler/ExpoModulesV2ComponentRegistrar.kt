package io.github.expo.modules.v2.compiler

import io.github.expo.modules.v2.compiler.fir.ExpoModulesV2FirRegistrar
import io.github.expo.modules.v2.compiler.ir.ExpoModulesV2IrGenerationExtension
import io.github.expo.modules.v2.compiler.ir.ModuleHintScanner
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import java.io.File

class ExpoModulesV2ComponentRegistrar : ExpoCompilerPluginRegistrar() {
  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    FirExtensionRegistrarAdapter.registerExtension(ExpoModulesV2FirRegistrar())

    val hints = ModuleHintScanner { configuration.jvmClasspathRoots }
    val typegenOutput = configuration.get(ExpoModulesV2ConfigurationKeys.TYPEGEN_OUTPUT)?.let(::File)
    IrGenerationExtension.registerExtension(ExpoModulesV2IrGenerationExtension(hints, typegenOutput))
  }
}
