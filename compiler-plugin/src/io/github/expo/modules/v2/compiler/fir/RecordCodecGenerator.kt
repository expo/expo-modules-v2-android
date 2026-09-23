package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.RecordKey
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
 * Declares the codec of an `@Record` class:
 *
 * ```
 * Point                                    the user's class, + Record supertype (RecordSupertypeGenerator)
 * ├── RecordCodec$ExpoModulesV2            generated, private, : RecordCodec<Point>
 * │   ├── <init>()
 * │   ├── recordClass: Class<Point>        override
 * │   ├── schema: RecordSchema             override
 * │   ├── encode(value, writer)            override
 * │   └── decode(reader): Point            override
 * └── Companion                            the user's, or generated when absent
 *     └── recordCodec$ExpoModulesV2: RecordCodec<Point>
 * ```
 */
class RecordCodecGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (!classSymbol.isRecord(session) || !classSymbol.canCarryACodec()) {
      return emptySet()
    }

    return buildSet {
      add(Identifiers.Names.CODEC_CLASS)

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
    if (!owner.isRecord(session) || !owner.canCarryACodec()) {
      return null
    }

    return when (name) {
      Identifiers.Names.CODEC_CLASS ->
        createNestedClass(owner, name, RecordKey, ClassKind.CLASS) {
          visibility = Visibilities.Private
          superType(codecTypeFor(owner))
        }.symbol

      SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT ->
        createCompanionObject(owner, RecordKey).symbol

      else -> null
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val owner = context.owner
    return when {
      owner.isGeneratedCodecClass() ->
        listOf(createConstructor(owner, RecordKey, isPrimary = true).symbol)

      owner.isGeneratedCompanion() ->
        listOf(createDefaultPrivateConstructor(owner, RecordKey).symbol)

      else -> emptyList()
    }
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> = when {
    classSymbol.isGeneratedCodecClass() -> setOf(
      SpecialNames.INIT,
      Identifiers.Names.RECORD_CLASS,
      Identifiers.Names.SCHEMA,
      Identifiers.Names.ENCODE,
      Identifiers.Names.DECODE,
    )

    classSymbol.isCompanionOfARecord() -> buildSet {
      add(Identifiers.Names.CODEC_PROPERTY)
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
      val record = owner.recordOwner() ?: return emptyList()
      return when (callableId.callableName) {
        Identifiers.Names.RECORD_CLASS -> listOf(
          createMemberProperty(
            owner,
            RecordKey,
            name = Identifiers.Names.RECORD_CLASS,
            returnType = javaClassType(record)
          ) {
            status { isOverride = true }
          }.symbol,
        )

        Identifiers.Names.SCHEMA -> listOf(
          createMemberProperty(
            owner,
            RecordKey,
            name = Identifiers.Names.SCHEMA,
            returnType = recordSchemaType()
          ) {
            status { isOverride = true }
          }.symbol,
        )

        else -> emptyList()
      }
    }

    if (owner.isCompanionOfARecord() && callableId.callableName == Identifiers.Names.CODEC_PROPERTY) {
      val record = owner.containingClass() as? FirClassSymbol<*> ?: return emptyList()
      return listOf(
        createMemberProperty(
          owner,
          RecordKey,
          name = Identifiers.Names.CODEC_PROPERTY,
          returnType = codecTypeFor(record)
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

    val record = owner.recordOwner() ?: return emptyList()
    val recordType = record.constructType(emptyArray(), isMarkedNullable = false)

    return when (callableId.callableName) {
      Identifiers.Names.ENCODE -> listOf(
        createMemberFunction(
          owner,
          RecordKey,
          name = Identifiers.Names.ENCODE,
          returnType = session.builtinTypes.unitType.coneType
        ) {
          status { isOverride = true }
          valueParameter(Name.identifier("value"), recordType)
          valueParameter(Name.identifier("writer"), coneType(Identifiers.Classes.RecordWriter))
        }.symbol,
      )

      Identifiers.Names.DECODE -> listOf(
        createMemberFunction(
          owner,
          RecordKey,
          name = Identifiers.Names.DECODE,
          returnType = recordType
        ) {
          status { isOverride = true }
          valueParameter(Name.identifier("reader"), coneType(Identifiers.Classes.RecordReader))
        }.symbol,
      )

      else -> emptyList()
    }
  }

  private fun FirClassSymbol<*>.canCarryACodec(): Boolean =
    classKind == ClassKind.CLASS && typeParameterSymbols.isEmpty()

  private fun FirClassSymbol<*>.hasCompanionObject(): Boolean =
    (this as? FirRegularClassSymbol)?.companionObjectSymbol != null

  private fun FirClassSymbol<*>.isGeneratedByUs(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == RecordKey

  private fun FirClassSymbol<*>.isGeneratedCodecClass(): Boolean =
    isGeneratedByUs() && name == Identifiers.Names.CODEC_CLASS

  private fun FirClassSymbol<*>.isGeneratedCompanion(): Boolean =
    isGeneratedByUs() && isCompanion

  private fun FirClassSymbol<*>.isCompanionOfARecord(): Boolean {
    if (!isCompanion) {
      return false
    }

    val containing = containingClass() as? FirClassSymbol<*> ?: return false
    return containing.isRecord(session) && containing.canCarryACodec()
  }

  private fun FirClassSymbol<*>.recordOwner(): FirClassSymbol<*>? =
    containingClass() as? FirClassSymbol<*>

  private fun coneType(classId: ClassId, vararg arguments: ConeKotlinType): ConeKotlinType =
    classId.createConeType(session, arrayOf(*arguments))

  private fun codecTypeFor(record: FirClassSymbol<*>): ConeKotlinType =
    coneType(
      Identifiers.Classes.RecordCodec,
      record.constructType(emptyArray(), isMarkedNullable = false)
    )

  private fun javaClassType(record: FirClassSymbol<*>): ConeKotlinType =
    ClassId.topLevel(FqName("java.lang.Class"))
      .createConeType(
        session,
        typeArguments = arrayOf(record.constructType(emptyArray(), isMarkedNullable = false)),
      )

  private fun recordSchemaType(): ConeKotlinType = coneType(Identifiers.Classes.RecordSchema)
}
