package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.UnionKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Declares the codec of an `@Union` type:
 *
 * ```
 * Dimension                                 the user's sealed type, + Union supertype (UnionSupertypeGenerator)
 * ├── UnionCodec$ExpoModulesV2              generated, : UnionCodec<Dimension>
 * │   ├── <init>()
 * │   ├── unionClass: Class<Dimension>      override
 * │   ├── schema: UnionSchema               override
 * │   ├── indexOf(value): Int               override
 * │   ├── unwrap(value): Any?               override
 * │   └── wrap(index, payload): Dimension   override
 * └── Companion                             the user's, or generated when absent
 *     └── unionCodec$ExpoModulesV2: UnionCodec<Dimension>
 * ```
 */
class UnionCodecGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (!classSymbol.canCarryAUnionCodec() || !classSymbol.isUnion(session)) {
      return emptySet()
    }

    return buildSet {
      add(Identifiers.Names.UNION_CODEC_CLASS)

      if (!classSymbol.hasCompanionObject()) {
        add(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
      }
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (!owner.canCarryAUnionCodec() || !owner.isUnion(session)) {
      return null
    }

    return when (name) {
      Identifiers.Names.UNION_CODEC_CLASS ->
        createNestedClass(owner, name, UnionKey, ClassKind.CLASS) {
          // A class nested in an interface is public on the JVM whatever it declares, so only a
          // sealed class gets to hide its codec.
          visibility = if (owner.classKind == ClassKind.INTERFACE) {
            Visibilities.Public
          } else {
            Visibilities.Private
          }
          superType(codecTypeFor(owner))
        }.symbol

      SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT ->
        createCompanionObject(owner, UnionKey).symbol

      else -> null
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val owner = context.owner
    return when {
      owner.isGeneratedCodecClass() ->
        listOf(createConstructor(owner, UnionKey, isPrimary = true).symbol)

      owner.isGeneratedCompanion() ->
        listOf(createDefaultPrivateConstructor(owner, UnionKey).symbol)

      else -> emptyList()
    }
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> = when {
    classSymbol.isGeneratedCodecClass() -> setOf(
      SpecialNames.INIT,
      Identifiers.Names.UNION_CLASS,
      Identifiers.Names.SCHEMA,
      Identifiers.Names.INDEX_OF,
      Identifiers.Names.UNWRAP,
      Identifiers.Names.WRAP,
    )

    classSymbol.isCompanionOfAUnion() -> buildSet {
      add(Identifiers.Names.UNION_CODEC_PROPERTY)
      // A user-declared companion already has its own constructor
      if (classSymbol.isGeneratedByUs()) {
        add(SpecialNames.INIT)
      }
    }

    else -> emptySet()
  }

  override fun generateProperties(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirPropertySymbol> {
    val owner = context?.owner ?: return emptyList()

    if (owner.isGeneratedCodecClass()) {
      val union = owner.unionOwner() ?: return emptyList()
      return when (callableId.callableName) {
        Identifiers.Names.UNION_CLASS -> listOf(
          createMemberProperty(
            owner,
            UnionKey,
            name = Identifiers.Names.UNION_CLASS,
            returnType = javaClassType(union)
          ) {
            status { isOverride = true }
          }.symbol,
        )

        Identifiers.Names.SCHEMA -> listOf(
          createMemberProperty(
            owner,
            UnionKey,
            name = Identifiers.Names.SCHEMA,
            returnType = coneType(Identifiers.Classes.UnionSchema)
          ) {
            status { isOverride = true }
          }.symbol,
        )

        else -> emptyList()
      }
    }

    if (owner.isCompanionOfAUnion() && callableId.callableName == Identifiers.Names.UNION_CODEC_PROPERTY) {
      val union = owner.containingClass() as? FirClassSymbol<*> ?: return emptyList()
      return listOf(
        createMemberProperty(
          owner,
          UnionKey,
          name = Identifiers.Names.UNION_CODEC_PROPERTY,
          returnType = codecTypeFor(union)
        ).symbol,
      )
    }

    return emptyList()
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val owner = context?.owner ?: return emptyList()
    if (!owner.isGeneratedCodecClass()) {
      return emptyList()
    }

    val union = owner.unionOwner() ?: return emptyList()
    val unionType = union.constructType(emptyArray(), isMarkedNullable = false)
    val builtins = session.builtinTypes

    return when (callableId.callableName) {
      Identifiers.Names.INDEX_OF -> listOf(
        createMemberFunction(owner, UnionKey, Identifiers.Names.INDEX_OF, builtins.intType.coneType) {
          status { isOverride = true }
          valueParameter(Name.identifier("value"), unionType)
        }.symbol,
      )

      Identifiers.Names.UNWRAP -> listOf(
        createMemberFunction(
          owner,
          UnionKey,
          Identifiers.Names.UNWRAP,
          builtins.nullableAnyType.coneType,
        ) {
          status { isOverride = true }
          valueParameter(Name.identifier("value"), unionType)
        }.symbol,
      )

      Identifiers.Names.WRAP -> listOf(
        createMemberFunction(owner, UnionKey, Identifiers.Names.WRAP, unionType) {
          status { isOverride = true }
          valueParameter(Name.identifier("index"), builtins.intType.coneType)
          valueParameter(Name.identifier("payload"), builtins.nullableAnyType.coneType)
        }.symbol,
      )

      else -> emptyList()
    }
  }

  private fun FirClassSymbol<*>.hasCompanionObject(): Boolean =
    (this as? FirRegularClassSymbol)?.companionObjectSymbol != null

  private fun FirClassSymbol<*>.isGeneratedByUs(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == UnionKey

  private fun FirClassSymbol<*>.isGeneratedCodecClass(): Boolean =
    isGeneratedByUs() && name == Identifiers.Names.UNION_CODEC_CLASS

  private fun FirClassSymbol<*>.isGeneratedCompanion(): Boolean =
    isGeneratedByUs() && isCompanion

  private fun FirClassSymbol<*>.isCompanionOfAUnion(): Boolean {
    if (!isCompanion) {
      return false
    }

    val containing = containingClass() as? FirClassSymbol<*> ?: return false
    return containing.canCarryAUnionCodec() && containing.isUnion(session)
  }

  private fun FirClassSymbol<*>.unionOwner(): FirClassSymbol<*>? =
    containingClass() as? FirClassSymbol<*>

  private fun coneType(classId: ClassId, vararg arguments: ConeKotlinType): ConeKotlinType =
    classId.createConeType(session, arrayOf(*arguments))

  private fun codecTypeFor(union: FirClassSymbol<*>): ConeKotlinType =
    coneType(
      Identifiers.Classes.UnionCodec,
      union.constructType(emptyArray(), isMarkedNullable = false)
    )

  private fun javaClassType(union: FirClassSymbol<*>): ConeKotlinType =
    ClassId.topLevel(FqName("java.lang.Class"))
      .createConeType(
        session,
        typeArguments = arrayOf(union.constructType(emptyArray(), isMarkedNullable = false)),
      )
}

/**
 * An interface or class without type parameters. `@Union` also requires it to be sealed, which
 * [UnionCheckers] reports: modality is not resolved yet when supertypes and members are generated.
 */
internal fun FirClassSymbol<*>.canCarryAUnionCodec(): Boolean =
  (classKind == ClassKind.INTERFACE || classKind == ClassKind.CLASS) &&
    typeParameterSymbols.isEmpty()
