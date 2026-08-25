package expo.modules.v2.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

class ExpoModulesV2IrGenerationExtension : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = SymbolFinder(pluginContext)
    val poet = TypeDescriptorPoet(pluginContext, symbols, moduleFragment)
    moduleFragment.acceptChildrenVoid(RecordCodecIrTransformer(pluginContext, symbols, poet))
    moduleFragment.acceptChildrenVoid(JSModuleIrTransformer(pluginContext, symbols, poet))
  }
}
