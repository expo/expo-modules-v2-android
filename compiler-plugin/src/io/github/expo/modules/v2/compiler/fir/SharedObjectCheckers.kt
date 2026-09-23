package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.fir.diagnostics.SharedObjectDiagnostics
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirConstructorChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol

/**
 * Frontend validation for `@ExpoSharedObject`, and for `@JS` on a constructor.
 */
class SharedObjectCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(SharedObjectClassChecker)
    override val constructorCheckers: Set<FirConstructorChecker> =
      setOf(SharedObjectConstructorChecker)
  }

  private object SharedObjectClassChecker : ExpoDeclarationChecker<FirRegularClass>() {
    override fun checkDeclaration(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.hasExpoSharedObjectAnnotation(session)) {
        return
      }

      // The two annotations describe two different bases, so a class is one or the other.
      if (declaration.symbol.expoModuleAnnotation(session) != null) {
        reporter.reportOn(
          declaration.source,
          SharedObjectDiagnostics.SHARED_OBJECT_WITH_EXPO_MODULE_ANNOTATION,
          context,
        )
        return
      }

      val shapeIsValid = checkExportedClassShape(
        declaration,
        SharedObjectDiagnostics.SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION,
        context,
        reporter,
        rejectObject = true,
      )
      if (!shapeIsValid) {
        return
      }

      if (declaration.typeParameters.isNotEmpty()) {
        reporter.reportOn(
          declaration.source,
          SharedObjectDiagnostics.SHARED_OBJECT_WITH_TYPE_PARAMETERS,
          context,
        )
        return
      }

      if (declaration.symbol.isSubtypeOfClass(Identifiers.Classes.Module, session)) {
        reporter.reportOn(
          declaration.source,
          SharedObjectDiagnostics.SHARED_OBJECT_EXTENDS_MODULE,
          context,
        )
        return
      }
      if (!declaration.symbol.isSharedObject(session)) {
        reporter.reportOn(
          declaration.source,
          SharedObjectDiagnostics.SHARED_OBJECT_IS_NOT_A_SHARED_OBJECT,
          context,
        )
        return
      }

      reportDuplicateExportNames(declaration, session, context, reporter)
    }
  }

  /**
   * `@JS` on a constructor picks the one JavaScript's `new` calls. It only means something on a
   * shared object, and only one constructor of a class can carry it - a class with a single public
   * constructor needs no mark at all.
   */
  private object SharedObjectConstructorChecker : ExpoDeclarationChecker<FirConstructor>() {
    override fun checkDeclaration(
      declaration: FirConstructor,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.hasJsAnnotation(session)) {
        return
      }

      val owner = declaration.containingClass() as? FirRegularClassSymbol
      if (owner == null || !owner.hasExpoSharedObjectAnnotation(session)) {
        reporter.reportOn(
          declaration.source,
          SharedObjectDiagnostics.SHARED_OBJECT_CONSTRUCTOR_OUTSIDE_SHARED_OBJECT,
          context,
        )
        return
      }

      // The first annotated constructor is the exposed one; a second is ambiguous.
      val annotated = owner.declarationSymbols
        .filterIsInstance<FirConstructorSymbol>()
        .filter { it.hasJsAnnotation(session) }
      if (annotated.size > 1 && annotated.first() != declaration.symbol) {
        reporter.reportOn(
          declaration.source,
          SharedObjectDiagnostics.SHARED_OBJECT_DUPLICATE_CONSTRUCTOR,
          context,
        )
      }
    }
  }
}

/**
 * Whether JavaScript's `new` can reach a constructor of this class.
 *
 * Mirrors what the backend picks: a `@JS`-marked constructor, or a single public one when nothing
 * is marked.
 */
internal fun FirRegularClassSymbol.hasConstructorForJavaScript(session: FirSession): Boolean {
  val constructors = declarationSymbols.filterIsInstance<FirConstructorSymbol>()
  return constructors.any { it.hasJsAnnotation(session) } ||
    constructors.singleOrNull()?.visibility == Visibilities.Public
}

/**
 * Whether JavaScript's `new` reaches *this* constructor - the backend's rule, asked of one
 * constructor rather than of the class.
 */
internal fun FirConstructor.isExportedToJavaScript(session: FirSession): Boolean {
  val owner = containingClass() as? FirRegularClassSymbol ?: return false
  if (!owner.hasExpoSharedObjectAnnotation(session)) {
    return false
  }
  if (symbol.hasJsAnnotation(session)) {
    return true
  }
  val constructors = owner.declarationSymbols.filterIsInstance<FirConstructorSymbol>()
  return constructors.none { it.hasJsAnnotation(session) } &&
    constructors.singleOrNull()?.visibility == Visibilities.Public
}
