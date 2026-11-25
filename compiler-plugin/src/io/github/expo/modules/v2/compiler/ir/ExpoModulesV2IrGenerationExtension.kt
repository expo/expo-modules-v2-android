package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.ClasspathPackage
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class ExpoModulesV2IrGenerationExtension(
  private val hints: ModuleHintScanner,
  /** The `classpathPackage` options: the package of the modules in each classpath entry. */
  private val classpathPackages: List<ClasspathPackage> = emptyList(),
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
      ModuleDiscoveryIrTransformer(pluginContext, symbols, poet, hints, moduleFragment, classpathPackages)
    )
    transformers.forEach(moduleFragment::transformChildrenVoid)
  }
}
