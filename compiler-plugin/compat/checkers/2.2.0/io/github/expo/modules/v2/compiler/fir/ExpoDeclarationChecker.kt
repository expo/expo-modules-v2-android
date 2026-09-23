package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirDeclaration

/**
 * The base of every checker in this plugin, so each one is written once against a signature that
 * does not change between Kotlin releases.
 *
 * Kotlin 2.2.0 - 2.2.10 variant: `check` takes the context and the reporter as plain parameters.
 */
abstract class ExpoDeclarationChecker<D : FirDeclaration> : FirDeclarationChecker<D>(MppCheckerKind.Common) {
  abstract fun checkDeclaration(declaration: D, context: CheckerContext, reporter: DiagnosticReporter)

  final override fun check(declaration: D, context: CheckerContext, reporter: DiagnosticReporter) {
    checkDeclaration(declaration, context, reporter)
  }
}
