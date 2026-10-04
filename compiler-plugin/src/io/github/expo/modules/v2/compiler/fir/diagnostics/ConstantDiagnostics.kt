package io.github.expo.modules.v2.compiler.fir.diagnostics

import io.github.expo.modules.v2.compiler.fir.ExpoDiagnosticsContainer
import io.github.expo.modules.v2.compiler.fir.expoRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.ConstantCheckers] reports.
 */
object ConstantDiagnostics : ExpoDiagnosticsContainer() {
  val CONSTANT_ON_NON_EXPORTED_PROPERTY by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val CONSTANT_ON_MUTABLE_PROPERTY by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = ConstantDiagnosticMessages

  init {
    registerRenderer()
  }
}

object ConstantDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP by expoRendererMap("ExpoModulesV2") {
    put(
      ConstantDiagnostics.CONSTANT_ON_NON_EXPORTED_PROPERTY,
      "@Constant only says something about a property JavaScript reads, and this one is not " +
        "exported - annotate it with @JS, or drop @Constant",
    )
    put(
      ConstantDiagnostics.CONSTANT_ON_MUTABLE_PROPERTY,
      "@Constant cannot be on a `var`: JavaScript keeps a constant's first value, so a write would " +
        "never reach Kotlin - make it a `val`, or drop @Constant",
    )
  }
}
