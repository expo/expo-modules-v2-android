package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.fir.diagnostics.ConstantDiagnostics
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

/**
 * Frontend validation for `@Constant`. JavaScript reads a constant once and keeps the value, so it
 * needs a `@JS` getter and nothing to write it with.
 */
class ConstantCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val propertyCheckers: Set<FirPropertyChecker> = setOf(ConstantPropertyChecker)
  }

  private object ConstantPropertyChecker : ExpoDeclarationChecker<FirProperty>() {
    override fun checkDeclaration(
      declaration: FirProperty,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (declaration.symbol.constantAnnotation(session) == null) {
        return
      }

      val diagnostic = when {
        !declaration.symbol.hasJsAnnotation(session) ->
          ConstantDiagnostics.CONSTANT_ON_NON_EXPORTED_PROPERTY
        declaration.isVar -> ConstantDiagnostics.CONSTANT_ON_MUTABLE_PROPERTY
        else -> return
      }
      reporter.reportOn(declaration.source, diagnostic, context)
    }
  }
}

internal fun FirBasedSymbol<*>.constantAnnotation(session: FirSession): FirAnnotation? =
  getAnnotationByClassId(Identifiers.Classes.ConstantAnnotation, session)
