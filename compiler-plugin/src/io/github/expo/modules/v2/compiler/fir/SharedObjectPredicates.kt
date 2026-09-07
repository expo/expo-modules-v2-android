package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol

class SharedObjectPredicates(session: FirSession) : FirExtensionSessionComponent(session) {
  val predicate: DeclarationPredicate = DeclarationPredicate.create {
    annotated(Identifiers.FqNames.SHARED_OBJECT_ANNOTATION)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }
}

val FirSession.sharedObjectPredicates: SharedObjectPredicates by
  FirSession.sessionComponentAccessor<SharedObjectPredicates>()

/**
 * Whether this class is declared `@ExpoSharedObject`.
 *
 * The resolved annotation is asked as well as the predicate, because the predicate index is keyed
 * by the short name written at the use site and so misses an import alias.
 */
fun FirClassSymbol<*>.hasExpoSharedObjectAnnotation(session: FirSession): Boolean =
  session.predicateBasedProvider.matches(session.sharedObjectPredicates.predicate, this) ||
    getAnnotationByClassId(Identifiers.Classes.ExpoSharedObjectAnnotation, session) != null

