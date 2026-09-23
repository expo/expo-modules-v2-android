package io.github.expo.modules.v2.compiler.fir.diagnostics

import io.github.expo.modules.v2.compiler.fir.ExpoDiagnosticsContainer
import io.github.expo.modules.v2.compiler.fir.expoRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.JSCheckers] reports about an
 * exported member.
 */
object JSDiagnostics : ExpoDiagnosticsContainer() {
  val JS_MEMBER_OUTSIDE_MODULE by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val JS_MEMBER_IS_INTERNAL by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val JS_UNSUPPORTED_FUNCTION_SHAPE by error1<KtDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val JS_DUPLICATE_EXPORT_NAME by error1<KtDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val JS_RESERVED_EXPORT_NAME by error1<KtDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = JSDiagnosticMessages

  init {
    registerRenderer()
  }
}

object JSDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP by expoRendererMap("ExpoModulesV2") {
    put(
      JSDiagnostics.JS_MEMBER_OUTSIDE_MODULE,
      "@JS on a member only exports it when the class itself is annotated @ExpoModule or " +
        "@ExpoSharedObject - annotate the class",
    )
    put(
      JSDiagnostics.JS_MEMBER_IS_INTERNAL,
      "A @JS member cannot be `internal` - the JVM appends a module suffix to its name, so the " +
        "bridge's method lookup would never find it. Make it public or private",
    )
    put(
      JSDiagnostics.JS_UNSUPPORTED_FUNCTION_SHAPE,
      "A @JS function cannot {0}",
      CommonRenderers.STRING,
    )
    put(
      JSDiagnostics.JS_DUPLICATE_EXPORT_NAME,
      "Two exports of this class are both named ''{0}'' in JavaScript - rename one, or give it a " +
        "different @JS(name = \"...\")",
      CommonRenderers.STRING,
    )
    put(
      JSDiagnostics.JS_RESERVED_EXPORT_NAME,
      "''{0}'' is reserved: every module and shared object carries the event emitter members " +
        "addListener, removeListener, removeAllListeners, listenerCount and emit. Rename the " +
        "export, or give it a different name with the annotation's name argument",
      CommonRenderers.STRING,
    )
  }
}
