package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType

/**
 * Adds `io.github.expo.modules.v2.unions.Union` to every `@Union` type.
 */
class UnionSupertypeGenerator(session: FirSession) : FirSupertypeGenerationExtension(session) {
  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    val classSymbol = declaration.symbol as? FirClassSymbol<*> ?: return false
    return classSymbol.canCarryAUnionCodec() && classSymbol.isUnion(session)
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    if (resolvedSupertypes.any { it.coneType.classId == Identifiers.Classes.UnionInterface }) {
      return emptyList()
    }

    return listOf(
      Identifiers.Classes.UnionInterface.constructClassLikeType(emptyArray(), isMarkedNullable = false),
    )
  }
}
