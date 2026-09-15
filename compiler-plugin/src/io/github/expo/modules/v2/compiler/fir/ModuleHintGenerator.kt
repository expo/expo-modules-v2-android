package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.ModuleHintKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Leaves a hint for every `@ExpoModule` this compilation declares:
 *
 * ```
 * package io.github.expo.modules.v2.hints
 *
 * interface expo_modules_v2demo_ExpoV2Demo_<hash> {     generated, : nothing
 *   fun module(): expo.modules.v2demo.ExpoV2Demo          abstract - the return type is the payload
 * }
 * ```
 */
@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
class ModuleHintGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  private val hints: Map<ClassId, FirRegularClassSymbol> by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(session.expoModulePredicates.predicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .associateBy { Identifiers.Hints.classIdFor(it.classId) }
  }

  override fun hasPackage(packageFqName: FqName): Boolean =
    packageFqName == Identifiers.Hints.PACKAGE

  override fun getTopLevelClassIds(): Set<ClassId> = hints.keys

  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    if (classId !in hints) {
      return null
    }
    return createTopLevelClass(classId, ModuleHintKey, ClassKind.INTERFACE).symbol
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> = if (classSymbol.isHint()) {
    setOf(Identifiers.Hints.MEMBER)
  } else {
    emptySet()
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val owner = context?.owner ?: return emptyList()
    if (!owner.isHint() || callableId.callableName != Identifiers.Hints.MEMBER) {
      return emptyList()
    }
    val module = hints[owner.classId]
      ?: return emptyList()

    val function = createMemberFunction(
      owner,
      ModuleHintKey,
      Identifiers.Hints.MEMBER,
      returnType = module.constructType(
        emptyArray(),
        isMarkedNullable = false
      ),
    ) {
      modality = Modality.ABSTRACT
    }

    return listOf(function.symbol)
  }

  private fun FirClassSymbol<*>.isHint(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == ModuleHintKey
}
