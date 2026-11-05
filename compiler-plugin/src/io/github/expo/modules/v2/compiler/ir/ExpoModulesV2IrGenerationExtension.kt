package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.typegen.Typegen
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import java.io.File

class ExpoModulesV2IrGenerationExtension(
  private val hints: ModuleHintScanner,
  /** Where to write the surface for typegen, or null to write none. */
  private val typegenOutput: File? = null,
  private val messages: MessageCollector = MessageCollector.NONE,
) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = SymbolFinder(pluginContext)

    // First, while the IR is still what the user wrote: the transformers below add to it.
    typegenOutput?.let { Typegen.write(moduleFragment, pluginContext, symbols, it, messages) }

    val poet = TypeDescriptorPoet(pluginContext, symbols, moduleFragment)

    val visitors = listOf(
      RecordCodecIrTransformer(pluginContext, symbols, poet),
      ExpoModuleIrTransformer(symbols, pluginContext, poet),
      ExpoSharedObjectIrTransformer(pluginContext, symbols, poet)
    )
    visitors.forEach(moduleFragment::acceptChildrenVoid)

    val transformers = listOf(
      ModuleDiscoveryIrTransformer(pluginContext, symbols, poet, hints, moduleFragment)
    )
    transformers.forEach(moduleFragment::transformChildrenVoid)
  }
}
