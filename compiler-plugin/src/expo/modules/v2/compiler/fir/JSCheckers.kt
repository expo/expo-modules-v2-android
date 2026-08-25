package expo.modules.v2.compiler.fir

import expo.modules.v2.compiler.Identifiers
import expo.modules.v2.compiler.fir.diagnostics.JSDiagnostics
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.name.Name

/**
 * Frontend validation for `@JS`.
 */
class JSCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(JsModuleChecker)
    override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker> = setOf(JsFunctionChecker)
    override val propertyCheckers: Set<FirPropertyChecker> = setOf(JsPropertyChecker)
  }

  private object JsModuleChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    override fun check(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.hasJsAnnotation(session)) {
        return
      }

      val rejected = when {
        declaration.classKind == ClassKind.INTERFACE -> "an interface"
        declaration.classKind == ClassKind.ENUM_CLASS -> "an enum class"
        declaration.classKind == ClassKind.ANNOTATION_CLASS -> "an annotation class"
        declaration.status.modality == Modality.ABSTRACT -> "an abstract class"
        declaration.status.modality == Modality.SEALED -> "a sealed class"
        declaration.isInner -> "an inner class"
        declaration.isLocal -> "a local class"
        else -> null
      }

      if (rejected != null) {
        reporter.reportOn(
          declaration.source,
          JSDiagnostics.JS_ON_UNSUPPORTED_DECLARATION,
          rejected,
          context,
        )
        return
      }

      if (declaration.typeParameters.isNotEmpty()) {
        reporter.reportOn(declaration.source, JSDiagnostics.JS_CLASS_WITH_TYPE_PARAMETERS, context)
        return
      }

      val moduleType = Identifiers.Classes.Module.constructClassLikeType(
        emptyArray(),
        isMarkedNullable = false,
      )
      if (!declaration.symbol.defaultType().isSubtypeOf(moduleType, session)) {
        reporter.reportOn(declaration.source, JSDiagnostics.JS_CLASS_IS_NOT_A_MODULE, context)
        return
      }

      reportDuplicateExportNames(declaration, session, context, reporter)
    }

    private fun reportDuplicateExportNames(
      declaration: FirRegularClass,
      session: FirSession,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val seen = mutableSetOf<String>()
      for (member in declaration.declarations) {
        if (member !is FirSimpleFunction && member !is FirProperty) {
          continue
        }
        val annotation = member.jsAnnotation(session) ?: continue
        val name = annotation.exportName(session)
          ?: (member as FirCallableDeclaration).symbol.name
        if (!seen.add(name.asString())) {
          reporter.reportOn(
            member.source,
            JSDiagnostics.JS_DUPLICATE_EXPORT_NAME,
            name.asString(),
            context,
          )
        }
      }
    }
  }

  private object JsFunctionChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    override fun check(
      declaration: FirSimpleFunction,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.hasJsAnnotation(session)) {
        return
      }

      if (declaration.moduleContainer(session) == null) {
        reporter.reportOn(declaration.source, JSDiagnostics.JS_MEMBER_OUTSIDE_MODULE, context)
        return
      }

      if (!checkVisibility(declaration, context, reporter)) {
        return
      }

      val parameters = declaration.valueParameters
      val shape = when {
        declaration.typeParameters.isNotEmpty() ->
          "declare type parameters - the bridge resolves one JVM signature per export"

        declaration.receiverParameter != null ->
          "have an extension receiver - the bridge invokes it on the module instance"

        parameters.any { it.isVararg } ->
          "declare a `vararg` parameter - the bridge passes a fixed argument list"

        parameters.any { it.defaultValue != null } ->
          "give a parameter a default value - JavaScript arguments are matched by position and " +
            "the bridge always passes every one"

        parameters.size > Identifiers.MAX_ARGUMENTS ->
          "declare more than ${Identifiers.MAX_ARGUMENTS} arguments (this one declares " +
            "${parameters.size})"

        else -> null
      }
      if (shape != null) {
        reporter.reportOn(
          declaration.source,
          JSDiagnostics.JS_UNSUPPORTED_FUNCTION_SHAPE,
          shape,
          context,
        )
        return
      }
    }
  }

  private object JsPropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {
    override fun check(
      declaration: FirProperty,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.hasJsAnnotation(session)) {
        return
      }

      if (declaration.isLocal) {
        reporter.reportOn(declaration.source, JSDiagnostics.JS_MEMBER_OUTSIDE_MODULE, context)
        return
      }

      if (declaration.moduleContainer(session) == null) {
        reporter.reportOn(declaration.source, JSDiagnostics.JS_MEMBER_OUTSIDE_MODULE, context)
        return
      }

      if (!checkVisibility(declaration, context, reporter)) {
        return
      }

      if (declaration.receiverParameter != null) {
        reporter.reportOn(
          declaration.source,
          JSDiagnostics.JS_UNSUPPORTED_FUNCTION_SHAPE,
          "have an extension receiver - the bridge invokes it on the module instance",
          context,
        )
        return
      }
    }
  }
}


internal fun FirDeclaration.jsAnnotation(session: FirSession): FirAnnotation? =
  getAnnotationByClassId(Identifiers.Classes.JSAnnotation, session)

internal fun FirBasedSymbol<*>.jsAnnotation(session: FirSession): FirAnnotation? =
  getAnnotationByClassId(Identifiers.Classes.JSAnnotation, session)

/** The `name` argument, or null when it is absent or empty. */
internal fun FirAnnotation.exportName(session: FirSession): Name? =
  getStringArgument(Identifiers.Names.ARG_NAME, session)
    ?.takeIf { it.isNotEmpty() }
    ?.let(Name::identifier)


/**
 * The `@JS` class this member is exported from, or null when there is none.
 */
private fun FirDeclaration.moduleContainer(session: FirSession): FirRegularClassSymbol? {
  val container = symbolOrNull()?.getContainingClassSymbol() as? FirRegularClassSymbol ?: return null
  return container.takeIf { it.jsAnnotation(session) != null }
}

private fun FirDeclaration.symbolOrNull() = when (this) {
  is FirCallableDeclaration -> symbol
  else -> null
}

// TODO(@lukmccall): consider forcing them to be public
/** `internal` members are name-mangled on the JVM, so the bridge's method lookup cannot find them. */
private fun checkVisibility(
  declaration: FirCallableDeclaration,
  context: CheckerContext,
  reporter: DiagnosticReporter,
): Boolean {
  if (declaration.visibility == Visibilities.Internal) {
    reporter.reportOn(declaration.source, JSDiagnostics.JS_MEMBER_IS_INTERNAL, context)
    return false
  }
  return true
}
