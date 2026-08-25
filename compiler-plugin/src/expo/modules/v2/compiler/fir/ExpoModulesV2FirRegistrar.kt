package expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class ExpoModulesV2FirRegistrar : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +::RecordPredicates
    +::JSPredicates
    +::RecordSupertypeGenerator
    +::RecordCodecGenerator
    +::RecordCheckers
    +::JSCheckers
  }
}
