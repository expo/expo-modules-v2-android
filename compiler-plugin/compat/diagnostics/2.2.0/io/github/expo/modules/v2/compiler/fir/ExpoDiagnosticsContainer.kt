package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory

/**
 * The base of every diagnostics object in this plugin.
 *
 * Kotlin 2.2.0 - 2.2.10 variant: factories are free-standing, and their renderer is registered with
 * the global [RootDiagnosticRendererFactory]. A subclass calls [registerRenderer] at the end of its
 * `init`, once its factories exist.
 */
abstract class ExpoDiagnosticsContainer {
  abstract fun getRendererFactory(): BaseDiagnosticRendererFactory

  protected fun registerRenderer() {
    RootDiagnosticRendererFactory.registerFactory(getRendererFactory())
  }
}

/** The messages of a [BaseDiagnosticRendererFactory], built on first use. */
fun expoRendererMap(
  name: String,
  init: KtDiagnosticFactoryToRendererMap.() -> Unit,
): Lazy<KtDiagnosticFactoryToRendererMap> = lazy { KtDiagnosticFactoryToRendererMap(name).apply(init) }
