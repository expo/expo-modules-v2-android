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
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.SharedObjectCheckers] reports.
 */
object SharedObjectDiagnostics {
  val SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val SHARED_OBJECT_WITH_TYPE_PARAMETERS by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val SHARED_OBJECT_WITH_EXPO_MODULE_ANNOTATION by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val SHARED_OBJECT_EXTENDS_MODULE by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val SHARED_OBJECT_IS_NOT_A_SHARED_OBJECT by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val SHARED_OBJECT_CONSTRUCTOR_OUTSIDE_SHARED_OBJECT by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val SHARED_OBJECT_DUPLICATE_CONSTRUCTOR by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )

  init {
    RootDiagnosticRendererFactory.registerFactory(SharedObjectDiagnosticMessages)
  }
}

object SharedObjectDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP = KtDiagnosticFactoryToRendererMap("ExpoModulesV2").apply {
    put(
      SharedObjectDiagnostics.SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION,
      "@ExpoSharedObject only applies to a non-abstract, non-inner class - this is {0}",
      CommonRenderers.STRING,
    )
    put(
      SharedObjectDiagnostics.SHARED_OBJECT_WITH_TYPE_PARAMETERS,
      "@ExpoSharedObject cannot be used on a generic class - an exported class is described once " +
        "under one name, and JavaScript has no way to name a type argument",
    )
    put(
      SharedObjectDiagnostics.SHARED_OBJECT_WITH_EXPO_MODULE_ANNOTATION,
      "A class is either a module or a shared object - drop @ExpoModule and keep " +
        "@ExpoSharedObject, or the other way round",
    )
    put(
      SharedObjectDiagnostics.SHARED_OBJECT_EXTENDS_MODULE,
      "A @ExpoSharedObject class cannot extend io.github.expo.modules.v2.Module - a module is " +
        "registered under its own name, never held as a reference. Annotate it @ExpoModule instead",
    )
    put(
      SharedObjectDiagnostics.SHARED_OBJECT_IS_NOT_A_SHARED_OBJECT,
      "A @ExpoSharedObject class must extend io.github.expo.modules.v2.SharedObject, " +
        "which is where the hooks JavaScript's reference counting calls live. A " +
        "io.github.expo.modules.v2.SharedRef counts, since it extends that itself",
    )
    put(
      SharedObjectDiagnostics.SHARED_OBJECT_CONSTRUCTOR_OUTSIDE_SHARED_OBJECT,
      "@JS on a constructor only means something on an @ExpoSharedObject class - a module is " +
        "registered, never constructed from JavaScript",
    )
    put(
      SharedObjectDiagnostics.SHARED_OBJECT_DUPLICATE_CONSTRUCTOR,
      "A shared object can expose only one constructor to JavaScript - mark just the one `new` " +
        "should call with @JS",
    )
  }
}
