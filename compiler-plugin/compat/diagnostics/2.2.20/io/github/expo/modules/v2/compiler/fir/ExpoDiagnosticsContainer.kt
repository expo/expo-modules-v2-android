package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer

/**
 * The base of every diagnostics object in this plugin.
 *
 * Kotlin 2.2.20+ variant: factories belong to a [KtDiagnosticsContainer], which owns their renderer
 * through `getRendererFactory()`. The global renderer registry is gone, so [registerRenderer] has
 * nothing to do.
 */
abstract class ExpoDiagnosticsContainer : KtDiagnosticsContainer() {
  protected fun registerRenderer() = Unit
}

/**
 * The messages of a `BaseDiagnosticRendererFactory`, built on first use. The map's constructor is
 * internal from 2.2.20; this factory function is its public replacement.
 */
fun expoRendererMap(
  name: String,
  init: KtDiagnosticFactoryToRendererMap.() -> Unit,
): Lazy<KtDiagnosticFactoryToRendererMap> = KtDiagnosticFactoryToRendererMap(name) { map -> map.init() }
