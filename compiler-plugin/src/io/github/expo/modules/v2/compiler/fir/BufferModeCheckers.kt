package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.fir.diagnostics.BufferModeDiagnostics
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirConstructorChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

/**
 * Frontend validation for `@BufferMode`, which only means something on a declaration whose values
 * actually cross the bridge.
 */
class BufferModeCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(BufferModeClassChecker)
    override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker> =
      setOf(BufferModeFunctionChecker)
    override val propertyCheckers: Set<FirPropertyChecker> = setOf(BufferModePropertyChecker)
    override val constructorCheckers: Set<FirConstructorChecker> =
      setOf(BufferModeConstructorChecker)
  }

  private object BufferModeClassChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    override fun check(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      val annotation = declaration.symbol.bufferModeAnnotation(session) ?: return

      val isExported = declaration.symbol.expoModuleAnnotation(session) != null ||
        declaration.symbol.hasExpoSharedObjectAnnotation(session)
      if (!isExported) {
        reporter.reportOn(
          declaration.source,
          BufferModeDiagnostics.BUFFER_MODE_ON_NON_EXPORTED_DECLARATION,
          context,
        )
        return
      }

      // A class is a default for its members; it has no result of its own to narrow.
      reportReturnsWithoutResult(annotation, declaration.source, context, reporter)
    }
  }

  private object BufferModeFunctionChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    override fun check(
      declaration: FirSimpleFunction,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      val isExported = declaration.symbol.hasJsAnnotation(session)

      if (!isExported && declaration.symbol.bufferModeAnnotation(session) != null) {
        reporter.reportOn(
          declaration.source,
          BufferModeDiagnostics.BUFFER_MODE_ON_NON_EXPORTED_DECLARATION,
          context,
        )
      }

      checkValueParameters(declaration, isExported, context, reporter)
    }
  }

  private object BufferModePropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {
    override fun check(
      declaration: FirProperty,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (declaration.symbol.bufferModeAnnotation(session) == null) {
        return
      }

      // An event's payload crosses like a property read, so @BufferMode steers it the same way.
      val isExported = declaration.symbol.hasJsAnnotation(session) ||
        declaration.symbol.hasEventAnnotation(session)
      if (!isExported) {
        reporter.reportOn(
          declaration.source,
          BufferModeDiagnostics.BUFFER_MODE_ON_NON_EXPORTED_DECLARATION,
          context,
        )
      }
    }
  }

  /** A constructor's arguments cross the bridge only when JavaScript's `new` reaches it. */
  private object BufferModeConstructorChecker : FirConstructorChecker(MppCheckerKind.Common) {
    override fun check(
      declaration: FirConstructor,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val isExported = declaration.isExportedToJavaScript(context.session)
      checkValueParameters(declaration, isExported, context, reporter)
    }
  }

  private companion object {
    fun checkValueParameters(
      declaration: FirFunction,
      isExported: Boolean,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      for (parameter in declaration.valueParameters) {
        val annotation = parameter.symbol.bufferModeAnnotation(context.session) ?: continue
        if (!isExported) {
          reporter.reportOn(
            parameter.source,
            BufferModeDiagnostics.BUFFER_MODE_ON_NON_EXPORTED_DECLARATION,
            context,
          )
          continue
        }
        // One argument is one crossing; the result belongs to the member that returns it.
        reportReturnsWithoutResult(annotation, parameter.source, context, reporter)
      }
    }

    fun reportReturnsWithoutResult(
      annotation: FirAnnotation,
      source: org.jetbrains.kotlin.KtSourceElement?,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      if (annotation.argumentMapping.mapping.containsKey(Identifiers.Names.ARG_RETURNS)) {
        reporter.reportOn(
          source,
          BufferModeDiagnostics.BUFFER_MODE_RETURNS_WITHOUT_RESULT,
          context,
        )
      }
    }
  }
}

internal fun FirBasedSymbol<*>.bufferModeAnnotation(session: FirSession): FirAnnotation? =
  getAnnotationByClassId(Identifiers.Classes.BufferModeAnnotation, session)
