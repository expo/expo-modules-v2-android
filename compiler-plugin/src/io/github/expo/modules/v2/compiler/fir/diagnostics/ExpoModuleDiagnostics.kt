package io.github.expo.modules.v2.compiler.fir.diagnostics

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration

/**
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.ExpoModuleCheckers] reports.
 */
object ExpoModuleDiagnostics {
  val EXPO_MODULE_ON_UNSUPPORTED_DECLARATION by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val EXPO_MODULE_WITH_TYPE_PARAMETERS by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val EXPO_MODULE_IS_NOT_A_MODULE by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val EXPO_MODULE_IS_A_SHARED_OBJECT by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val EXPO_MODULE_UNEXPOSABLE_CLASS by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )

  init {
    RootDiagnosticRendererFactory.registerFactory(ExpoModuleDiagnosticMessages)
  }
}

object ExpoModuleDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP = KtDiagnosticFactoryToRendererMap("ExpoModulesV2").apply {
    put(
      ExpoModuleDiagnostics.EXPO_MODULE_ON_UNSUPPORTED_DECLARATION,
      "@ExpoModule only applies to a non-abstract, non-inner class or object - this is {0}",
      CommonRenderers.STRING,
    )
    put(
      ExpoModuleDiagnostics.EXPO_MODULE_WITH_TYPE_PARAMETERS,
      "@ExpoModule cannot be used on a generic class - a module is registered once under one " +
        "name, and JavaScript has no way to name a type argument",
    )
    put(
      ExpoModuleDiagnostics.EXPO_MODULE_IS_NOT_A_MODULE,
      "An @ExpoModule class must extend io.github.expo.modules.v2.Module - that is the " +
        "receiver the bridge invokes a module's methods on",
    )
    put(
      ExpoModuleDiagnostics.EXPO_MODULE_IS_A_SHARED_OBJECT,
      "A shared-object class is annotated @ExpoSharedObject, not @ExpoModule - a module is registered " +
        "under its own name rather than held as a reference",
    )
    put(
      ExpoModuleDiagnostics.EXPO_MODULE_UNEXPOSABLE_CLASS,
      "@ExpoModule(classes = [...]) cannot expose ''{0}''",
      CommonRenderers.STRING,
    )
  }
}
