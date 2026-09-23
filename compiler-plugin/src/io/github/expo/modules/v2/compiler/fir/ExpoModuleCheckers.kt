package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.fir.diagnostics.ExpoModuleDiagnostics
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol

/**
 * Frontend validation for `@ExpoModule`.
 */
class ExpoModuleCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(ExpoModuleClassChecker)
  }

  private object ExpoModuleClassChecker : ExpoDeclarationChecker<FirRegularClass>() {
    override fun checkDeclaration(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (declaration.symbol.expoModuleAnnotation(session) == null) {
        return
      }

      // `@ExpoSharedObject` owns the other side, and reports its own diagnostics on this class.
      if (declaration.symbol.hasExpoSharedObjectAnnotation(session)) {
        return
      }

      val shapeIsValid = checkExportedClassShape(
        declaration,
        ExpoModuleDiagnostics.EXPO_MODULE_ON_UNSUPPORTED_DECLARATION,
        context,
        reporter,
      )
      if (!shapeIsValid) {
        return
      }

      if (declaration.typeParameters.isNotEmpty()) {
        reporter.reportOn(
          declaration.source,
          ExpoModuleDiagnostics.EXPO_MODULE_WITH_TYPE_PARAMETERS,
          context,
        )
        return
      }

      // A module's object lives at `expo.modules.<name>`, and `Module` is the receiver the bridge
      // invokes its methods on. A class JavaScript holds a reference to is a shared object instead.
      if (!declaration.symbol.isSubtypeOfClass(Identifiers.Classes.Module, session)) {
        val diagnostic = if (declaration.symbol.isSharedObject(session)) {
          ExpoModuleDiagnostics.EXPO_MODULE_IS_A_SHARED_OBJECT
        } else {
          ExpoModuleDiagnostics.EXPO_MODULE_IS_NOT_A_MODULE
        }
        reporter.reportOn(declaration.source, diagnostic, context)
        return
      }

      reportDuplicateExportNames(declaration, session, context, reporter)
      reportUnexposableClasses(declaration, session, context, reporter)
    }

    /**
     * Every entry of `@ExpoModule(classes = [...])` has to be something a class object can be built
     * from.
     *
     * Reported rather than skipped: a listed class that cannot be exposed is a mistake, where a
     * *nested* class without an annotated constructor is simply a shared object that happens to
     * live there, and is left alone.
     */
    private fun reportUnexposableClasses(
      declaration: FirRegularClass,
      session: FirSession,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val annotation = declaration.symbol.expoModuleAnnotation(session) ?: return

      for (listed in annotation.classListArgument(Identifiers.Names.ARG_CLASSES)) {
        val reason = when {
          !listed.hasExpoSharedObjectAnnotation(session) -> "it is not annotated @ExpoSharedObject"
          !listed.hasConstructorForJavaScript(session) ->
            "it exposes no constructor - mark one with @JS, or leave it a single public one"

          else -> continue
        }

        reporter.reportOn(
          declaration.source,
          ExpoModuleDiagnostics.EXPO_MODULE_UNEXPOSABLE_CLASS,
          listed.name.asString() + ": " + reason,
          context,
        )
      }
    }
  }
}
