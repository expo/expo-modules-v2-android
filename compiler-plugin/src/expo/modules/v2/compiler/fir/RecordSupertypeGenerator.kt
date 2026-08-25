package expo.modules.v2.compiler.fir

import expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType

/**
 * Adds `expo.modules.v2.records.Record` to every `@Record` class.
 */
class RecordSupertypeGenerator(session: FirSession) : FirSupertypeGenerationExtension(session) {
  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    val classSymbol = declaration.symbol as? FirClassSymbol<*> ?: return false
    return classSymbol.classKind == ClassKind.CLASS && classSymbol.isRecord(session)
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    if (resolvedSupertypes.any { it.coneType.classId == Identifiers.Classes.RecordInterface }) {
      return emptyList()
    }

    return listOf(
      Identifiers.Classes.RecordInterface.constructClassLikeType(emptyArray(), isMarkedNullable = false),
    )
  }
}
