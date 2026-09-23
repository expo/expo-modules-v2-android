package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.eventJsName
import io.github.expo.modules.v2.compiler.fir.diagnostics.JSDiagnostics
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Frontend validation for `@JS`, which exports a member of an `@ExpoModule` or `@ExpoSharedObject`
 * class. The class annotations are checked by [ExpoModuleCheckers] and [SharedObjectCheckers].
 */
class JSCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val functionCheckers: Set<FirDeclarationChecker<FirFunction>> =
      setOf(JsFunctionChecker)
    override val propertyCheckers: Set<FirPropertyChecker> = setOf(JsPropertyChecker)
  }

  private object JsFunctionChecker : ExpoDeclarationChecker<FirFunction>() {
    override fun checkDeclaration(
      declaration: FirFunction,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      // Registered for every function, so the checker set is spelled the same in every supported
      // Kotlin release (2.4.20 renamed the simple-function one); only named functions are exported.
      if (declaration.symbol !is FirNamedFunctionSymbol) {
        return
      }

      val session = context.session
      if (!declaration.symbol.hasJsAnnotation(session)) {
        return
      }

      if (declaration.exportContainer(session) == null) {
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

  private object JsPropertyChecker : ExpoDeclarationChecker<FirProperty>() {
    override fun checkDeclaration(
      declaration: FirProperty,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.hasJsAnnotation(session)) {
        return
      }

      if (declaration.isLocalProperty) {
        reporter.reportOn(declaration.source, JSDiagnostics.JS_MEMBER_OUTSIDE_MODULE, context)
        return
      }

      if (declaration.exportContainer(session) == null) {
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

/** Whether this class extends `SharedObject`, so JavaScript can hold a reference to one. */
internal fun FirRegularClassSymbol.isSharedObject(session: FirSession): Boolean =
  isSubtypeOfClass(Identifiers.Classes.SharedObject, session)

internal fun FirRegularClassSymbol.isSubtypeOfClass(
  classId: ClassId,
  session: FirSession,
): Boolean = defaultType().isSubtypeOf(
  classId.constructClassLikeType(emptyArray(), isMarkedNullable = false),
  session,
)

/** The classes an `Array<KClass<*>>` annotation argument names. */
internal fun FirAnnotation.classListArgument(name: Name): List<FirRegularClassSymbol> {
  val argument = argumentMapping.mapping[name] ?: return emptyList()
  val elements = argument.collectionLiteralElements() ?: listOf(argument)
  return elements.mapNotNull { element ->
    // `Foo::class` resolves to a qualifier that already carries the symbol, so there is no type to
    // pick apart.
    ((element as? FirGetClassCall)?.argument as? FirResolvedQualifier)
      ?.classSymbol as? FirRegularClassSymbol
  }
}

internal fun FirDeclaration.jsAnnotation(session: FirSession): FirAnnotation? =
  getAnnotationByClassId(Identifiers.Classes.JSAnnotation, session)

internal fun FirDeclaration.eventAnnotation(session: FirSession): FirAnnotation? =
  getAnnotationByClassId(Identifiers.Classes.EventAnnotation, session)

internal fun FirBasedSymbol<*>.sharedObjectAnnotation(session: FirSession): FirAnnotation? =
  getAnnotationByClassId(Identifiers.Classes.ExpoSharedObjectAnnotation, session)

internal fun FirBasedSymbol<*>.expoModuleAnnotation(session: FirSession): FirAnnotation? =
  getAnnotationByClassId(Identifiers.Classes.ExpoModuleAnnotation, session)

/** The `name` argument, or null when it is absent or empty. */
internal fun FirAnnotation.exportName(session: FirSession): Name? =
  stringArgument(Identifiers.Names.ARG_NAME, session)
    ?.takeIf { it.isNotEmpty() }
    ?.let(Name::identifier)

/**
 * The shape both `@ExpoModule` and `@ExpoSharedObject` require of a class. Returns false once it has
 * reported.
 */
internal fun checkExportedClassShape(
  declaration: FirRegularClass,
  diagnostic: KtDiagnosticFactory1<String>,
  context: CheckerContext,
  reporter: DiagnosticReporter,
  rejectObject: Boolean = false,
): Boolean {
  val rejected = when {
    declaration.classKind == ClassKind.INTERFACE -> "an interface"
    declaration.classKind == ClassKind.ENUM_CLASS -> "an enum class"
    declaration.classKind == ClassKind.ANNOTATION_CLASS -> "an annotation class"
    rejectObject && declaration.classKind == ClassKind.OBJECT -> "an object"
    declaration.status.modality == Modality.ABSTRACT -> "an abstract class"
    declaration.status.modality == Modality.SEALED -> "a sealed class"
    declaration.isInner -> "an inner class"
    declaration.isLocal -> "a local class"
    else -> null
  } ?: return true

  reporter.reportOn(declaration.source, diagnostic, rejected, context)
  return false
}

/**
 * The `@ExpoModule` or `@ExpoSharedObject` class this member is exported from, or null when there is
 * none.
 */
internal fun FirDeclaration.exportContainer(session: FirSession): FirRegularClassSymbol? {
  val container = symbolOrNull()?.containingClass() as? FirRegularClassSymbol ?: return null
  return container.takeIf {
    it.expoModuleAnnotation(session) != null || it.sharedObjectAnnotation(session) != null
  }
}

private fun FirDeclaration.symbolOrNull() = when (this) {
  is FirCallableDeclaration -> symbol
  else -> null
}

// TODO(@lukmccall): consider forcing them to be public
/** `internal` members are name-mangled on the JVM, so the bridge's method lookup cannot find them. */
internal fun checkVisibility(
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

/**
 * Reports two exports of one class that would land on the same JavaScript name - functions,
 * properties and events share one namespace - and an export named after one of the event emitter's
 * own members, which every module object and shared-object prototype carries.
 */
internal fun reportDuplicateExportNames(
  declaration: FirRegularClass,
  session: FirSession,
  context: CheckerContext,
  reporter: DiagnosticReporter,
) {
  val seen = mutableSetOf<String>()
  for (member in declaration.declarations) {
    val isNamedFunction = (member as? FirFunction)?.symbol is FirNamedFunctionSymbol
    if (!isNamedFunction && member !is FirProperty) {
      continue
    }
    val name = member.exportNameOrNull(session) ?: continue
    if (name in Identifiers.Literals.RESERVED_EXPORT_NAMES) {
      reporter.reportOn(member.source, JSDiagnostics.JS_RESERVED_EXPORT_NAME, name, context)
      continue
    }
    if (!seen.add(name)) {
      reporter.reportOn(member.source, JSDiagnostics.JS_DUPLICATE_EXPORT_NAME, name, context)
    }
  }
}

/** The JavaScript name this member exports under, or null when it is not exported. */
private fun FirDeclaration.exportNameOrNull(session: FirSession): String? {
  val symbolName = (this as FirCallableDeclaration).symbol.name.asString()
  jsAnnotation(session)?.let { annotation ->
    return annotation.exportName(session)?.asString() ?: symbolName
  }
  if (this is FirProperty) {
    eventAnnotation(session)?.let { annotation ->
      return annotation.exportName(session)?.asString() ?: eventJsName(symbolName)
    }
  }
  return null
}
