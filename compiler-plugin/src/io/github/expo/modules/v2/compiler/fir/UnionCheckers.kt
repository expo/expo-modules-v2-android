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
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Frontend validation for `@Union`: the shape of the union and of each variant, and that JavaScript
 * can pick every variant - a value goes to the first variant declared that takes it, so a variant
 * is lost behind an earlier one that takes everything it would. Only this checks it: the runtime
 * trusts the codec this plugin generates.
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

      // A variant of an unsupported shape is reported on itself, by UnionVariantChecker.
      val shapes = variants.mapNotNull { variantShapeOf(it, session).takeIf { shape -> shape.isValid } }
      val unreachable = firstUnreachable(shapes, session) ?: return
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
      declaration.symbol.unionSupertype(session) ?: return

      val shape = variantShapeOf(declaration.symbol, session)
      shape.unsupported?.let { reason ->
        reporter.reportOn(declaration.source, UnionDiagnostics.UNION_UNSUPPORTED_VARIANT, reason, context)
        return
      }
      if (shape.nullableValue) {
        reporter.reportOn(declaration.source, UnionDiagnostics.UNION_VARIANT_WITH_NULLABLE_VALUE, context)
      }
    }
  }
}

/**
 * The kind of JavaScript value a variant takes. MUST classify like `variantKindOf` in `:api`'s
 * `ExpectedTypeDecoder.cpp`, which classifies the same type's codes at run time.
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
  /** The fields of a record variant an object must have to be taken. */
  val requiredFields: Set<String> = emptySet(),
  /** The type of the value a variant crosses as: what it wraps, or the record itself. */
  val valueClassId: ClassId? = null,
  /** What a value variant crosses as, type arguments included; null for an object or a record. */
  val valueType: ConeKotlinType? = null,
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
    return VariantShape(
      symbol,
      JsKind.OBJECT,
      tag,
      requiredFields = requiredFieldsOf(symbol),
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
  if (valueType.containsLambdaType()) {
    return VariantShape(
      symbol,
      unsupported = "a class holding a function type - a JavaScript function only crosses as a " +
        "parameter of a @JS function or constructor",
    )
  }
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
    valueType = valueType,
    valueClassId = valueType.classId,
  )
}

/** The fields of a record an object must have to be taken: what native code checks for. */
private fun requiredFieldsOf(record: FirRegularClassSymbol): Set<String> =
  record.declarationSymbols
    .filterIsInstance<FirConstructorSymbol>()
    .firstOrNull { it.isPrimary }
    ?.valueParameterSymbols
    .orEmpty()
    .filter {
      !it.hasDefaultValue &&
        !it.resolvedReturnType.isMarkedNullable &&
        it.resolvedReturnType.classId != Identifiers.Classes.JavaScriptValue
    }
    .mapTo(sortedSetOf()) { it.name.asString() }

private fun classSymbolOf(type: ConeKotlinType, session: FirSession): FirRegularClassSymbol? =
  type.classId?.let { session.symbolProvider.getClassLikeSymbolByClassId(it) as? FirRegularClassSymbol }

private fun ConeKotlinType.argument(index: Int): ConeKotlinType? =
  (typeArguments.getOrNull(index) as? ConeKotlinTypeProjection)?.type

private val LISTS = setOf(
  StandardClassIds.List, StandardClassIds.MutableList,
  StandardClassIds.Set, StandardClassIds.MutableSet,
  StandardClassIds.Collection, StandardClassIds.MutableCollection,
  StandardClassIds.Array,
)

private val PRIMITIVE_ARRAY_ELEMENTS: Map<ClassId, ClassId> = mapOf(
  ClassId.topLevel(FqName("kotlin.IntArray")) to StandardClassIds.Int,
  ClassId.topLevel(FqName("kotlin.LongArray")) to StandardClassIds.Long,
  ClassId.topLevel(FqName("kotlin.FloatArray")) to StandardClassIds.Float,
  ClassId.topLevel(FqName("kotlin.DoubleArray")) to StandardClassIds.Double,
  ClassId.topLevel(FqName("kotlin.BooleanArray")) to StandardClassIds.Boolean,
)

/** What a list, set or array holds, or null when [type] is not one of those. */
private fun elementOf(type: ConeKotlinType): ConeKotlinType? {
  val classId = type.classId ?: return null
  PRIMITIVE_ARRAY_ELEMENTS[classId]?.let { return it.constructClassLikeType(emptyArray(), isMarkedNullable = false) }
  return if (classId in LISTS) type.argument(0) else null
}

/** The same type up to its own nullability; its type arguments must match exactly. */
private fun sameType(a: ConeKotlinType, b: ConeKotlinType): Boolean {
  if (a.classId == null || a.classId != b.classId || a.typeArguments.size != b.typeArguments.size) {
    return false
  }
  return a.typeArguments.indices.all { index ->
    val mine = a.argument(index)
    val theirs = b.argument(index)
    mine != null && theirs != null && mine.isMarkedNullable == theirs.isMarkedNullable &&
      sameType(mine, theirs)
  }
}

private val OBJECT_KINDS = setOf(
  JsKind.ARRAY, JsKind.BINARY, JsKind.OBJECT, JsKind.SHARED_OBJECT, JsKind.ANY_OBJECT,
)

/**
 * Whether a value of type [earlier] takes every JavaScript value one of type [later] would. It looks
 * into type arguments: `List<Int>` and `List<String>` take different arrays. MUST stay in sync with
 * what the `:api` converters refuse while a union tries a variant (`convertUnion`).
 */
private fun covers(earlier: ConeKotlinType, later: ConeKotlinType, session: FirSession): Boolean {
  val myClass = classSymbolOf(earlier, session)
  val theirClass = classSymbolOf(later, session)
  val mine = jsKindOf(earlier, myClass, session)
  if (later.isMarkedNullable && !earlier.isMarkedNullable && mine != JsKind.ANY) {
    return false
  }
  if (sameType(earlier, later)) {
    return true
  }
  // A nested union, which only the same union covers.
  mine ?: return false
  if (mine == JsKind.ANY) {
    return true
  }
  val theirs = jsKindOf(later, theirClass, session) ?: return false
  if (mine == JsKind.ANY_OBJECT) {
    return theirs in OBJECT_KINDS
  }
  if (mine != theirs) {
    return false
  }

  return when (mine) {
    JsKind.BOOLEAN, JsKind.BINARY -> true
    // Only reached inside a list or map: there a number is not checked against its type, so a plain
    // number takes every number - List<Int> truncates [0.5]. An Int-backed enum takes only its
    // entries, and Kotlin hands the rest to the next variant.
    JsKind.NUMBER -> earlier.classId in NUMBERS

    JsKind.STRING -> coversStrings(earlier.classId, later.classId)
    JsKind.ARRAY -> {
      val myElement = elementOf(earlier) ?: return true
      val theirElement = elementOf(later) ?: return true
      covers(myElement, theirElement, session)
    }

    JsKind.OBJECT -> {
      val myMap = earlier.classId in MAPS
      val theirMap = later.classId in MAPS
      when {
        myMap && theirMap -> {
          val myValue = earlier.argument(1) ?: return true
          val theirValue = later.argument(1) ?: return true
          covers(myValue, theirValue, session)
        }

        myMap -> true
        theirMap -> false
        else -> {
          val myRecord = myClass ?: return false
          val theirRecord = theirClass ?: return false
          requiredFieldsOf(theirRecord).containsAll(requiredFieldsOf(myRecord))
        }
      }
    }

    JsKind.SHARED_OBJECT -> {
      val myShared = myClass ?: return false
      val theirShared = theirClass ?: return false
      if (theirShared != myShared && !theirShared.isSubtypeOfClass(myShared.classId, session)) {
        return false
      }
      // SharedRef<Drawable> and SharedRef<Bitmap>: native code cannot see what a ref carries, so it
      // offers each one to the first, whose conversion refuses a ref of another type.
      val myRef = earlier.argument(0)?.takeIf { myShared.classId == Identifiers.Classes.SharedRef }
      val theirRef = later.argument(0)?.takeIf { theirShared.classId == Identifiers.Classes.SharedRef }
      if (theirShared == myShared && myRef != null && theirRef != null) {
        val myRefClass = classSymbolOf(myRef, session) ?: return false
        val theirRefClass = classSymbolOf(theirRef, session) ?: return false
        return theirRefClass == myRefClass || theirRefClass.isSubtypeOfClass(myRefClass.classId, session)
      }
      true
    }

    else -> false
  }
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

    "kotlin.String", "java.io.File", "java.net.URL", "java.net.URI", "java.util.UUID",
    "java.nio.file.Path", "android.net.Uri", "kotlin.uuid.Uuid",
      -> JsKind.STRING

    "kotlin.collections.List", "kotlin.collections.MutableList",
    "kotlin.collections.Set", "kotlin.collections.MutableSet",
    "kotlin.collections.Collection", "kotlin.collections.MutableCollection",
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
  session: FirSession,
): String? {
  for (later in shapes.indices) {
    for (earlier in 0 until later) {
      val reason = shapes[earlier].shadows(shapes[later], session) ?: continue
      return "${shapes[later].name} is never picked - ${shapes[earlier].name}, declared before it, $reason"
    }
  }
  return null
}

/** The string types whose conversion takes every string, so nothing after them is ever picked. */
private val TOTAL_STRINGS = setOf(
  StandardClassIds.String,
  ClassId.topLevel(FqName("java.io.File")),
  ClassId.topLevel(FqName("android.net.Uri")),
)

/** `java.util.UUID` and `kotlin.uuid.Uuid`: every spelling either parses is a relative URI and a path. */
private val UUID_TYPES: Set<ClassId> =
  setOf(ClassId.topLevel(FqName("java.util.UUID")), ClassId.topLevel(FqName("kotlin.uuid.Uuid")))

/**
 * String types that refuse some strings, but take every string the types listed for them take, so
 * those are never picked after them. MUST stay in sync with what the converters refuse.
 */
private val STRING_COVERS: Map<ClassId, Set<ClassId>> = mapOf(
  ClassId.topLevel(FqName("java.net.URI")) to UUID_TYPES,
  ClassId.topLevel(FqName("java.nio.file.Path")) to UUID_TYPES,
)

/**
 * Whether a string variant of [earlier] takes every string one of [later] would: it takes every
 * string, or every string [later]'s conversion accepts (see [STRING_COVERS]).
 */
private fun coversStrings(earlier: ClassId?, later: ClassId?): Boolean {
  if (earlier in TOTAL_STRINGS) {
    return true
  }
  if (earlier == null || later == null) {
    return false
  }
  return STRING_COVERS[earlier]?.contains(later) == true
}

private val NUMBERS = setOf(
  StandardClassIds.Int,
  StandardClassIds.Long,
  StandardClassIds.Float,
  StandardClassIds.Double,
  ClassId.topLevel(FqName("kotlin.time.Duration")),
)

private val MAPS = setOf(StandardClassIds.Map, StandardClassIds.MutableMap)

/**
 * Why [later] is never picked with this variant declared before it, or null when it can be: a value
 * goes to the first variant declared that takes it. MUST stay in sync with
 * `convertUnion` in `:api`'s `UnionDispatch.h`, which offers a value to each variant in turn.
 */
private fun VariantShape.shadows(
  later: VariantShape,
  session: FirSession,
): String? {
  // A value goes to the first variant declared that takes it, so a catch-all takes everything
  // after it that it would: `Any` every value, `JavaScriptObject` every object.
  if (kind == JsKind.ANY) {
    return "takes every value"
  }
  if (kind == JsKind.ANY_OBJECT && later.kind in OBJECT_KINDS) {
    return "takes every object"
  }
  if (group == null || group != later.group) {
    return null
  }

  val takesAll = when (kind) {
    JsKind.BOOLEAN, JsKind.BINARY, JsKind.ANY_OBJECT, JsKind.ANY -> true

    // List<Int> leaves List<String> its arrays, and SharedRef<Drawable> leaves SharedRef<Bitmap>
    // its refs: these look into the type arguments.
    JsKind.ARRAY, JsKind.SHARED_OBJECT -> {
      val mine = valueType ?: return null
      val theirs = later.valueType ?: return null
      return "takes every value it would".takeIf { covers(mine, theirs, session) }
    }

    JsKind.LITERAL -> return "is the same string".takeIf { later.kind == kind && later.tag == tag }

    JsKind.STRING -> coversStrings(valueClassId, later.valueClassId)

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
      valueClassId in MAPS && later.valueClassId in MAPS -> {
        val mine = valueType ?: return null
        val theirs = later.valueType ?: return null
        return "takes every value it would".takeIf { covers(mine, theirs, session) }
      }

      valueClassId in MAPS -> true
      later.valueClassId in MAPS -> false
      else -> return "takes every object it would: it needs only $requiredFields".takeIf {
        later.requiredFields.containsAll(requiredFields)
      }
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
