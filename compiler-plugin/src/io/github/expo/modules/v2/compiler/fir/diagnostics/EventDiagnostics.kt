package io.github.expo.modules.v2.compiler.fir.diagnostics

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.EventCheckers] reports about an
 * `@Event` property.
 */
object EventDiagnostics {
  val EVENT_OUTSIDE_MODULE by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val EVENT_WITH_JS by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val EVENT_UNSUPPORTED_SHAPE by error1<KtDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val EVENT_WRONG_TYPE by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val EVENT_INITIALIZER_IS_NOT_EVENT_CALL by error0<KtDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )

  init {
    RootDiagnosticRendererFactory.registerFactory(EventDiagnosticMessages)
  }
}

object EventDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP = KtDiagnosticFactoryToRendererMap("ExpoModulesV2").apply {
    put(
      EventDiagnostics.EVENT_OUTSIDE_MODULE,
      "@Event on a property only exports it when the class itself is annotated @ExpoModule or " +
        "@ExpoSharedObject - annotate the class",
    )
    put(
      EventDiagnostics.EVENT_WITH_JS,
      "A property is either an @Event or a @JS export - an event is subscribed to through " +
        "addListener, never read as a value. Drop one of the two",
    )
    put(
      EventDiagnostics.EVENT_UNSUPPORTED_SHAPE,
      "An @Event property cannot {0}",
      CommonRenderers.STRING,
    )
    put(
      EventDiagnostics.EVENT_WRONG_TYPE,
      "An @Event property must be declared as io.github.expo.modules.v2.events.Event<T>, the " +
        "type event<T>(...) returns",
    )
    put(
      EventDiagnostics.EVENT_INITIALIZER_IS_NOT_EVENT_CALL,
      "An @Event property must be initialized with event<T>(...) - that is what ties the event to " +
        "this object, so an Event taken from elsewhere would emit on the wrong one",
    )
  }
}
