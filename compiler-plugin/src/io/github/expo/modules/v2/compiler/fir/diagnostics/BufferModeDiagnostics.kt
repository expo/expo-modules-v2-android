package io.github.expo.modules.v2.compiler.fir.diagnostics

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.BufferModeCheckers] reports.
 */
object BufferModeDiagnostics {
  val BUFFER_MODE_ON_NON_EXPORTED_DECLARATION by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val BUFFER_MODE_RETURNS_WITHOUT_RESULT by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )

  init {
    RootDiagnosticRendererFactory.registerFactory(BufferModeDiagnosticMessages)
  }
}

object BufferModeDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP = KtDiagnosticFactoryToRendererMap("ExpoModulesV2").apply {
    put(
      BufferModeDiagnostics.BUFFER_MODE_ON_NON_EXPORTED_DECLARATION,
      "@BufferMode only says something about a value that crosses the bridge, and nothing here " +
        "does - annotate a class with @ExpoModule or @ExpoSharedObject, and a member with @JS",
    )
    put(
      BufferModeDiagnostics.BUFFER_MODE_RETURNS_WITHOUT_RESULT,
      "@BufferMode(returns = ...) applies to a member's result, and this declaration has none - " +
        "drop it and leave the choice to `value`",
    )
  }
}
