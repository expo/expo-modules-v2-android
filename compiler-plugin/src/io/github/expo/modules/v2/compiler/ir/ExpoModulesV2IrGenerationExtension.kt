package io.github.expo.modules.v2.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class ExpoModulesV2IrGenerationExtension(
  private val hints: ModuleHintScanner,
  private val packageName: String? = null,
  private val messages: MessageCollector = MessageCollector.NONE,
) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = SymbolFinder(pluginContext)
    val poet = TypeDescriptorPoet(pluginContext, symbols, moduleFragment)

    val visitors = listOf(
      RecordCodecIrTransformer(pluginContext, symbols, poet),
      UnionCodecIrTransformer(pluginContext, symbols, poet),
      ExpoModuleIrTransformer(symbols, pluginContext, poet),
      ExpoSharedObjectIrTransformer(pluginContext, symbols, poet)
    )
    visitors.forEach(moduleFragment::acceptChildrenVoid)

    val transformers = listOf(
      ModuleDiscoveryIrTransformer(
        context = pluginContext,
        symbols = symbols,
        poet = poet,
        scanner = hints,
        moduleFragment = moduleFragment,
        ownPackage = packageName,
        messages = messages
      )
    )
    transformers.forEach(moduleFragment::transformChildrenVoid)
  }
}
