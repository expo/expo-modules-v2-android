package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class ExpoModulesV2FirRegistrar : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +::RecordPredicates
    +::JSPredicates
    +::SharedObjectPredicates
    +::EventPredicates
    +::RecordSupertypeGenerator
    +::RecordCodecGenerator
    +::RecordCheckers
    +::JSCheckers
    +::ExpoModuleCheckers
    +::BufferModeCheckers
    +::SharedObjectCheckers
    +::EventCheckers
  }
}
