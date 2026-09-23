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
 * Kotlin 2.2.20+ variant: `check` receives the context and the reporter as context parameters
 * (hence `-Xcontext-parameters` in the build for these releases).
 */
abstract class ExpoDeclarationChecker<D : FirDeclaration> : FirDeclarationChecker<D>(MppCheckerKind.Common) {
  abstract fun checkDeclaration(declaration: D, context: CheckerContext, reporter: DiagnosticReporter)

  context(context: CheckerContext, reporter: DiagnosticReporter)
  final override fun check(declaration: D) {
    checkDeclaration(declaration, context, reporter)
  }
}
