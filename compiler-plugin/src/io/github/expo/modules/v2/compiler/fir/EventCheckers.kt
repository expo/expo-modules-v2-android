package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.fir.diagnostics.EventDiagnostics
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.isLateInit
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.name.CallableId

/**
 * Frontend validation for `@Event`, which exports a property holding an `Event<T>` as an event
 * JavaScript subscribes to. The backend wraps the property's initializer, so the shapes accepted
 * here are exactly the ones that have one.
 */
class EventCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val propertyCheckers: Set<FirPropertyChecker> = setOf(EventPropertyChecker)
  }

  private object EventPropertyChecker : ExpoDeclarationChecker<FirProperty>() {
    override fun checkDeclaration(
      declaration: FirProperty,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.hasEventAnnotation(session)) {
        return
      }

      if (declaration.isLocalProperty || declaration.exportContainer(session) == null) {
        reporter.reportOn(declaration.source, EventDiagnostics.EVENT_OUTSIDE_MODULE, context)
        return
      }

      if (declaration.symbol.hasJsAnnotation(session)) {
        reporter.reportOn(declaration.source, EventDiagnostics.EVENT_WITH_JS, context)
        return
      }

      val shape = when {
        declaration.isVar -> "be a `var` - the event object is created once, with the instance"
        declaration.isLateInit -> "be `lateinit` - it has to be initialized with event<T>(...)"
        declaration.delegate != null ->
          "be delegated - it has to be initialized with event<T>(...) directly"

        declaration.receiverParameter != null ->
          "have an extension receiver - the event belongs to the module instance"

        declaration.fromPrimaryConstructor == true ->
          "be a constructor parameter - it has to be initialized with event<T>(...) in the class body"

        declaration.initializer == null ->
          "go without an initializer - it has to be initialized with event<T>(...)"

        declaration.getter.let { it != null && it !is FirDefaultPropertyAccessor } ->
          "have a custom getter - the plugin binds the initializer, which a getter would bypass"

        else -> null
      }
      if (shape != null) {
        reporter.reportOn(
          declaration.source,
          EventDiagnostics.EVENT_UNSUPPORTED_SHAPE,
          shape,
          context,
        )
        return
      }

      val type = declaration.returnTypeRef.coneType
      if (type.classId != Identifiers.Classes.Event || type.isMarkedNullable) {
        reporter.reportOn(declaration.source, EventDiagnostics.EVENT_WRONG_TYPE, context)
        return
      }

      val callee = (declaration.initializer as? FirFunctionCall)
        ?.calleeReference
        ?.toResolvedCallableSymbol()
        ?.callableId
      if (callee != EVENT_FACTORY) {
        reporter.reportOn(
          declaration.source,
          EventDiagnostics.EVENT_INITIALIZER_IS_NOT_EVENT_CALL,
          context,
        )
      }
    }

    /** `ExpoObject.event<T>(...)`, the only factory that ties an event to its owner. */
    private val EVENT_FACTORY = CallableId(Identifiers.Classes.ExpoObject, Identifiers.Names.EVENT)
  }
}
