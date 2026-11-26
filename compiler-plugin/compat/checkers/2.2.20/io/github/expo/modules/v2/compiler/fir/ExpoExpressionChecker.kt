package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirStatement

/**
 * The base of every expression checker in this plugin, the counterpart of [ExpoDeclarationChecker].
 *
 * Kotlin 2.2.20+ variant: `check` receives the context and the reporter as context parameters.
 */
abstract class ExpoExpressionChecker<E : FirStatement> : FirExpressionChecker<E>(MppCheckerKind.Common) {
  abstract fun checkExpression(expression: E, context: CheckerContext, reporter: DiagnosticReporter)

  context(context: CheckerContext, reporter: DiagnosticReporter)
  final override fun check(expression: E) {
    checkExpression(expression, context, reporter)
  }
}
