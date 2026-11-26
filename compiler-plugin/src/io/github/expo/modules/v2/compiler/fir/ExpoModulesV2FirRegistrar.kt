package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/** [packageName] is the plugin's `packageName` option, which the module hints record. */
class ExpoModulesV2FirRegistrar(
  private val packageName: String? = null
) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +::RecordPredicates
    +::UnionPredicates
    +::JSPredicates
    +::SharedObjectPredicates
    +::EventPredicates
    +::ExpoModulePredicates
    +::RecordSupertypeGenerator
    +::RecordCodecGenerator
    +::UnionSupertypeGenerator
    +::UnionCodecGenerator
    +FirDeclarationGenerationExtension.Factory { session -> ModuleHintGenerator(session, packageName) }
    +::RecordCheckers
    +::UnionCheckers
    +::JSCheckers
    +::ExpoModuleCheckers
    +::BufferModeCheckers
    +::SharedObjectCheckers
    +::EventCheckers
    +::ConstantCheckers
    +::ModuleDiscoveryCheckers
  }
}
