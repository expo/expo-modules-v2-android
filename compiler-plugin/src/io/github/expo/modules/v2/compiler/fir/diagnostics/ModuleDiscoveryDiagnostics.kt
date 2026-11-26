package io.github.expo.modules.v2.compiler.fir.diagnostics

import io.github.expo.modules.v2.compiler.fir.ExpoDiagnosticsContainer
import io.github.expo.modules.v2.compiler.fir.expoRendererMap
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtElement

/**
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.ModuleDiscoveryCheckers] reports.
 */
object ModuleDiscoveryDiagnostics : ExpoDiagnosticsContainer() {
  val MODULE_DISCOVERY_REFERENCE by error0<KtElement>()

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = ModuleDiscoveryDiagnosticMessages

  init {
    registerRenderer()
  }
}

object ModuleDiscoveryDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP by expoRendererMap("ExpoModulesV2") {
    put(
      ModuleDiscoveryDiagnostics.MODULE_DISCOVERY_REFERENCE,
      "discoveredExpoModules() and discoveredExpoPackages() can only be called directly: the compiler " +
        "plugin replaces each call with what it found, and a reference would still point at the " +
        "function, which throws - call it, for example in a lambda `{ discoveredExpoPackages() }`",
    )
  }
}
