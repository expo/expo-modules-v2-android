package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.fir.diagnostics.ModuleDiscoveryDiagnostics
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirCallableReferenceAccessChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol

/**
 * Frontend validation for `discoveredExpoModules()` and `discoveredExpoPackages()`. The IR
 * transformer replaces calls to them; a callable reference such as `::discoveredExpoPackages` is
 * not a call, so it would keep pointing at the function, which throws at runtime.
 */
class ModuleDiscoveryCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
    override val callableReferenceAccessCheckers: Set<FirCallableReferenceAccessChecker> =
      setOf(ModuleDiscoveryReferenceChecker)
  }

  private object ModuleDiscoveryReferenceChecker : ExpoExpressionChecker<FirCallableReferenceAccess>() {
    private val intrinsics = setOf(
      Identifiers.Callables.discoveredExpoModules,
      Identifiers.Callables.discoveredExpoPackages,
    )

    override fun checkExpression(
      expression: FirCallableReferenceAccess,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val callableId = expression.calleeReference.toResolvedCallableSymbol()?.callableId ?: return
      if (callableId in intrinsics) {
        reporter.reportOn(expression.source, ModuleDiscoveryDiagnostics.MODULE_DISCOVERY_REFERENCE, context)
      }
    }
  }
}
