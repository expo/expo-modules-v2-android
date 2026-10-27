package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.fir.diagnostics.UnionDiagnostics
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Frontend validation for `@Union`: the shape of the union and of each variant, and that JavaScript
 * can pick every variant - within one kind of value the first variant declared that takes it wins,
 * so a variant is lost behind an earlier one that takes everything it would. The runtime repeats the
 * last check for a codec this plugin did not generate, with the same rules (`UnionType.validate` in
 * `:api`).
 */
class UnionCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirRegularClassChecker> =
      setOf(UnionClassChecker, UnionVariantChecker)
  }

  private object UnionClassChecker : ExpoDeclarationChecker<FirRegularClass>() {
    override fun checkDeclaration(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.isUnion(session)) {
        return
      }

      if (!checkClassShape(declaration, context, reporter)) {
        return
      }

      checkNoHandWrittenCodec(declaration, context, reporter)

      // In the order they are declared, as the generated codec lists them: the frontend lists sealed
      // inheritors by name, and the first variant declared that takes a value is the one it goes to.
      val variants = declaration.getSealedClassInheritors(session)
        .mapNotNull { classId ->
          session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        }
        .sortedWith(
          compareBy(
            { session.firProvider.getFirClassifierContainerFileIfAny(it)?.name.orEmpty() },
            { it.source?.startOffset ?: 0 },
          ),
        )
      if (variants.isEmpty()) {
        reporter.reportOn(declaration.source, UnionDiagnostics.UNION_WITHOUT_VARIANTS, context)
        return
      }

      val discriminator = declaration
        .getAnnotationByClassId(Identifiers.Classes.UnionAnnotation, session)
        ?.stringArgument(Identifiers.Names.ARG_DISCRIMINATOR, session)
        ?.takeIf { it.isNotEmpty() }

      // A variant of an unsupported shape is reported on itself, by UnionVariantChecker.
      val shapes = variants.mapNotNull { variantShapeOf(it, session).takeIf { shape -> shape.isValid } }
      val unreachable = firstUnreachable(shapes, discriminator != null, session) ?: return
      reporter.reportOn(declaration.source, UnionDiagnostics.UNION_UNREACHABLE_VARIANT, unreachable, context)
    }

    private fun checkClassShape(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ): Boolean {
      val session = context.session
      val rejected = when {
        declaration.classKind == ClassKind.ENUM_CLASS -> "an enum class"
        declaration.classKind == ClassKind.ANNOTATION_CLASS -> "an annotation class"
        declaration.classKind == ClassKind.OBJECT -> "an object"
        declaration.symbol.isRecord(session) -> "an @Record class"
        declaration.status.modality != Modality.SEALED ->
          if (declaration.classKind == ClassKind.INTERFACE) "a non-sealed interface" else "a non-sealed class"
        declaration.isInner -> "an inner class"
        declaration.isLocal -> "a local class"
        else -> null
      }

      if (rejected != null) {
        reporter.reportOn(
          declaration.source,
          UnionDiagnostics.UNION_ON_UNSUPPORTED_DECLARATION,
          rejected,
          context,
        )
        return false
      }

      if (declaration.typeParameters.isNotEmpty()) {
        reporter.reportOn(declaration.source, UnionDiagnostics.UNION_WITH_TYPE_PARAMETERS, context)
        return false
      }

      return true
    }

    private fun checkNoHandWrittenCodec(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val companion = declaration.companionObjectSymbol ?: return
      val clashing = companion.declarationSymbols.any {
        (it as? FirCallableSymbol<*>)?.name == Identifiers.Names.UNION_CODEC_PROPERTY &&
          it.origin is FirDeclarationOrigin.Source
      }
      if (clashing) {
        reporter.reportOn(
          declaration.source,
          UnionDiagnostics.UNION_DECLARES_ITS_OWN_CODEC,
          Identifiers.Literals.UNION_CODEC_PROPERTY,
          context,
        )
      }
    }
  }

  private object UnionVariantChecker : ExpoDeclarationChecker<FirRegularClass>() {
    override fun checkDeclaration(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      val union = declaration.symbol.unionSupertype(session) ?: return

      val shape = variantShapeOf(declaration.symbol, session)
      shape.unsupported?.let { reason ->
        reporter.reportOn(declaration.source, UnionDiagnostics.UNION_UNSUPPORTED_VARIANT, reason, context)
        return
      }
      if (shape.nullableValue) {
        reporter.reportOn(declaration.source, UnionDiagnostics.UNION_VARIANT_WITH_NULLABLE_VALUE, context)
        return
      }

      val discriminator = union
        .getAnnotationByClassId(Identifiers.Classes.UnionAnnotation, session)
        ?.stringArgument(Identifiers.Names.ARG_DISCRIMINATOR, session)
        ?.takeIf { it.isNotEmpty() }
        ?: return

      if (shape.kind == JsKind.OBJECT && !shape.isRecord) {
        reporter.reportOn(
          declaration.source,
          UnionDiagnostics.UNION_VARIANT_IS_NOT_A_RECORD,
          declaration.name.asString(),
          context,
        )
        return
      }

      if (shape.isRecord && discriminator in shape.recordFields) {
        reporter.reportOn(
          declaration.source,
          UnionDiagnostics.UNION_VARIANT_SHADOWS_DISCRIMINATOR,
          discriminator,
          context,
        )
      }
    }
  }
}

/**
 * The kind of JavaScript value a variant takes. MUST classify like `UnionVariantKind.of` in `:api`,
 * which classifies the converter of the same type at run time.
 */
private enum class JsKind {
  BOOLEAN,
  NUMBER,
  STRING,
  ARRAY,
  BINARY,
  OBJECT,
  SHARED_OBJECT,
  ANY_OBJECT,
  ANY,
  LITERAL,
}

private class VariantShape(
  val symbol: FirRegularClassSymbol,
  val kind: JsKind? = null,
  val tag: String = symbol.name.asString(),
  val isRecord: Boolean = false,
  val recordFields: Set<String> = emptySet(),
  /** The fields of a record variant an object must have to be taken. */
  val requiredFields: Set<String> = emptySet(),
  /** The type of the value a variant crosses as: what it wraps, or the record itself. */
  val valueClassId: ClassId? = null,
  /** The class a shared-object variant holds. */
  val sharedClass: FirRegularClassSymbol? = null,
  val nullableValue: Boolean = false,
  val unsupported: String? = null,
) {
  val isValid: Boolean get() = unsupported == null && !nullableValue && kind != null

  val name: String get() = symbol.name.asString()

  /** Strings and `object` variants share one kind of JavaScript value. */
  val group: JsKind? get() = if (kind == JsKind.LITERAL) JsKind.STRING else kind
}

/** The `@Union` type [this] is a direct variant of, or null. */
private fun FirRegularClassSymbol.unionSupertype(session: FirSession): FirRegularClassSymbol? =
  resolvedSuperTypes.firstNotNullOfOrNull { supertype ->
    val classId = (supertype as? ConeClassLikeType)?.classId ?: return@firstNotNullOfOrNull null
    val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
    symbol?.takeIf { it.isUnion(session) }
  }

private fun variantShapeOf(symbol: FirRegularClassSymbol, session: FirSession): VariantShape {
  val tag = symbol.getAnnotationByClassId(Identifiers.Classes.TagAnnotation, session)
    ?.stringArgument(Identifiers.Names.ARG_VALUE, session)
    ?: symbol.name.asString()

  val unsupported = when {
    symbol.classKind == ClassKind.INTERFACE -> "an interface"
    symbol.classKind == ClassKind.ENUM_CLASS -> "an enum class"
    symbol.resolvedStatus.modality == Modality.SEALED ->
      "a sealed type - make each of its classes a variant of the union directly"
    symbol.resolvedStatus.modality == Modality.ABSTRACT -> "an abstract class"
    symbol.resolvedStatus.modality == Modality.OPEN -> "an open class"
    symbol.typeParameterSymbols.isNotEmpty() -> "a generic class"
    else -> null
  }
  if (unsupported != null) {
    return VariantShape(symbol, unsupported = unsupported)
  }

  if (symbol.classKind == ClassKind.OBJECT) {
    return VariantShape(symbol, JsKind.LITERAL, tag)
  }

  val constructor = symbol.declarationSymbols
    .filterIsInstance<FirConstructorSymbol>()
    .firstOrNull { it.isPrimary }

  if (symbol.isRecord(session)) {
    val parameters = constructor?.valueParameterSymbols.orEmpty()
    // What native code checks for before it hands an object to one of several record variants.
    val required = parameters.filter {
      !it.hasDefaultValue &&
        !it.resolvedReturnType.isMarkedNullable &&
        it.resolvedReturnType.classId != Identifiers.Classes.JavaScriptValue
    }
    return VariantShape(
      symbol,
      JsKind.OBJECT,
      tag,
      isRecord = true,
      recordFields = parameters.mapTo(mutableSetOf()) { it.name.asString() },
      requiredFields = required.mapTo(sortedSetOf()) { it.name.asString() },
      valueClassId = symbol.classId,
    )
  }

  val parameter = constructor?.valueParameterSymbols?.singleOrNull()
  val property = symbol.declarationSymbols
    .filterIsInstance<FirPropertySymbol>()
    .firstOrNull { it.name == parameter?.name }
  if (parameter == null || property == null) {
    return VariantShape(
      symbol,
      unsupported = "a class without exactly one constructor property - give it one `val`, or " +
        "make it an @Record",
    )
  }
  // The generated codec reads the property from outside the class.
  if (property.resolvedStatus.visibility == Visibilities.Private) {
    return VariantShape(symbol, unsupported = "a class whose one property is private")
  }

  val valueType = parameter.resolvedReturnType
  if (valueType.isMarkedNullable) {
    return VariantShape(symbol, nullableValue = true)
  }

  val valueClass = valueType.classId?.let {
    session.symbolProvider.getClassLikeSymbolByClassId(it) as? FirRegularClassSymbol
  }
  if (valueClass != null && valueClass.isUnion(session)) {
    return VariantShape(
      symbol,
      unsupported = "a class holding another @Union - make its variants variants of this union",
    )
  }

  val kind = jsKindOf(valueType, valueClass, session)
  return VariantShape(
    symbol,
    kind,
    tag,
    sharedClass = valueClass.takeIf { kind == JsKind.SHARED_OBJECT },
    valueClassId = valueType.classId,
  )
}

private fun jsKindOf(
  type: ConeKotlinType,
  valueClass: FirRegularClassSymbol?,
  session: FirSession,
): JsKind? {
  val name = type.classId?.asSingleFqName()?.asString() ?: return null
  return when (name) {
    "kotlin.Boolean" -> JsKind.BOOLEAN
    "kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.time.Duration" ->
      JsKind.NUMBER

    "kotlin.String", "java.io.File", "java.net.URL", "java.net.URI", "java.nio.file.Path" ->
      JsKind.STRING

    "kotlin.collections.List", "kotlin.collections.MutableList",
    "kotlin.collections.Set", "kotlin.collections.MutableSet",
    "kotlin.Array", "kotlin.IntArray", "kotlin.LongArray", "kotlin.FloatArray",
    "kotlin.DoubleArray", "kotlin.BooleanArray",
      -> JsKind.ARRAY

    "kotlin.ByteArray" -> JsKind.BINARY
    "kotlin.collections.Map", "kotlin.collections.MutableMap" -> JsKind.OBJECT
    "kotlin.Any" -> JsKind.ANY
    else -> when (type.classId) {
      Identifiers.Classes.JavaScriptValue -> JsKind.ANY
      Identifiers.Classes.JavaScriptObject, Identifiers.Classes.TypedArray -> JsKind.ANY_OBJECT
      else -> valueClass?.let { classKindOf(it, session) }
    }
  }
}

private fun classKindOf(valueClass: FirRegularClassSymbol, session: FirSession): JsKind? =
  when {
    valueClass.isRecord(session) -> JsKind.OBJECT
    valueClass.isSharedObject(session) -> JsKind.SHARED_OBJECT
    // Like the runtime's EnumConverter: an entry crosses as its one Int property, else a string.
    valueClass.classKind == ClassKind.ENUM_CLASS -> {
      // Not `entries`, which every enum declares as a static property.
      val properties = valueClass.declarationSymbols
        .filterIsInstance<FirPropertySymbol>()
        .filter { !it.resolvedStatus.isStatic }
      val intBacked = properties.singleOrNull()?.resolvedReturnType?.classId == StandardClassIds.Int
      if (intBacked) JsKind.NUMBER else JsKind.STRING
    }

    else -> null
  }

/** Why the first variant JavaScript never picks is never picked, or null when it picks them all. */
private fun firstUnreachable(
  shapes: List<VariantShape>,
  hasDiscriminator: Boolean,
  session: FirSession,
): String? {
  for (later in shapes.indices) {
    for (earlier in 0 until later) {
      val reason = shapes[earlier].shadows(shapes[later], hasDiscriminator, session) ?: continue
      return "${shapes[later].name} is never picked - ${shapes[earlier].name}, declared before it, $reason"
    }
  }
  return null
}

private val TOTAL_STRINGS = setOf(StandardClassIds.String, ClassId.topLevel(FqName("java.io.File")))

private val NUMBERS = setOf(
  StandardClassIds.Int,
  StandardClassIds.Long,
  StandardClassIds.Float,
  StandardClassIds.Double,
  ClassId.topLevel(FqName("kotlin.time.Duration")),
)

private val MAPS = setOf(StandardClassIds.Map, StandardClassIds.MutableMap)

/**
 * Why [later] is never picked with this variant declared before it, or null when it can be. Within a
 * kind, the first variant declared that takes a value gets it. MUST stay in sync with
 * `VariantShape.shadows` in `:api`, which repeats this check at run time.
 */
private fun VariantShape.shadows(
  later: VariantShape,
  hasDiscriminator: Boolean,
  session: FirSession,
): String? {
  if (group == null || group != later.group) {
    return null
  }

  val takesAll = when (kind) {
    JsKind.BOOLEAN, JsKind.ARRAY, JsKind.BINARY, JsKind.ANY_OBJECT, JsKind.ANY -> true

    JsKind.LITERAL -> return "is the same string".takeIf { later.kind == kind && later.tag == tag }

    JsKind.STRING -> valueClassId in TOTAL_STRINGS

    JsKind.NUMBER -> when (valueClassId) {
      StandardClassIds.Long ->
        if (later.valueClassId == StandardClassIds.Int || later.valueClassId == StandardClassIds.Long) {
          return "takes every whole number it would"
        } else {
          false
        }

      // A plain Int takes every whole number in its range; an Int-backed enum only its entries.
      StandardClassIds.Int -> later.valueClassId == StandardClassIds.Int || later.valueClassId !in NUMBERS
      in NUMBERS -> true
      else -> false
    }

    JsKind.OBJECT -> when {
      hasDiscriminator -> return "has the same tag '$tag'".takeIf { later.tag == tag }
      valueClassId in MAPS -> true
      later.valueClassId in MAPS -> false
      else -> return "takes every object it would: it needs only $requiredFields".takeIf {
        later.requiredFields.containsAll(requiredFields)
      }
    }

    JsKind.SHARED_OBJECT -> {
      val mine = sharedClass ?: return null
      val theirs = later.sharedClass ?: return null
      theirs == mine || theirs.isSubtypeOfClass(mine.classId, session)
    }

    null -> false
  }

  return when {
    takesAll -> "takes every value it would"
    // The same type: whatever one refuses, so does the other.
    valueClassId != null && valueClassId == later.valueClassId -> "has the same type"
    else -> null
  }
}
