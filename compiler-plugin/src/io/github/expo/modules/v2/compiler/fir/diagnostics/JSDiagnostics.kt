package io.github.expo.modules.v2.compiler.fir.diagnostics

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.JSCheckers] reports.
 */
object JSDiagnostics {
  val JS_ON_UNSUPPORTED_DECLARATION by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val JS_CLASS_IS_NOT_A_MODULE by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val JS_CLASS_WITH_TYPE_PARAMETERS by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
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

  init {
    RootDiagnosticRendererFactory.registerFactory(JSDiagnosticMessages)
  }
}

object JSDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP = KtDiagnosticFactoryToRendererMap("ExpoModulesV2").apply {
    put(
      JSDiagnostics.JS_ON_UNSUPPORTED_DECLARATION,
      "@JS only applies to a non-abstract, non-inner class or object - this is {0}",
      CommonRenderers.STRING,
    )
    put(
      JSDiagnostics.JS_CLASS_IS_NOT_A_MODULE,
      "A @JS class must extend io.github.expo.modules.v2.modules.Module - that is what the registry " +
        "registers and what the bridge invokes methods on",
    )
    put(
      JSDiagnostics.JS_CLASS_WITH_TYPE_PARAMETERS,
      "@JS cannot be used on a generic class - a module is registered as one instance under one " +
        "name, and JavaScript has no way to name a type argument",
    )
    put(
      JSDiagnostics.JS_MEMBER_OUTSIDE_MODULE,
      "@JS on a member only exports it when the class itself is annotated @JS - annotate the class",
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
      "Two exports of this module are both named ''{0}'' in JavaScript - rename one, or give it a " +
        "different @JS(name = \"...\")",
      CommonRenderers.STRING,
    )
  }
}
