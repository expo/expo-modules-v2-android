package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol

class UnionPredicates(session: FirSession) : FirExtensionSessionComponent(session) {
  val predicate: DeclarationPredicate = DeclarationPredicate.create {
    annotated(Identifiers.FqNames.UNION_ANNOTATION)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }
}

val FirSession.unionPredicates: UnionPredicates by FirSession.sessionComponentAccessor<UnionPredicates>()

fun FirClassSymbol<*>.isUnion(session: FirSession): Boolean =
  session.predicateBasedProvider.matches(session.unionPredicates.predicate, this)
