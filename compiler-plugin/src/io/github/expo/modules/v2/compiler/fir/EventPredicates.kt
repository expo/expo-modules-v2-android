package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

class EventPredicates(session: FirSession) : FirExtensionSessionComponent(session) {
  val predicate: DeclarationPredicate = DeclarationPredicate.create {
    annotated(Identifiers.FqNames.EVENT_ANNOTATION)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }
}

val FirSession.eventPredicates: EventPredicates by
  FirSession.sessionComponentAccessor<EventPredicates>()

/**
 * Whether this property is declared `@Event`.
 *
 * The resolved annotation is asked as well as the predicate, because the predicate index is keyed
 * by the short name written at the use site and so misses an import alias.
 */
fun FirBasedSymbol<*>.hasEventAnnotation(session: FirSession): Boolean =
  session.predicateBasedProvider.matches(session.eventPredicates.predicate, this) ||
    getAnnotationByClassId(Identifiers.Classes.EventAnnotation, session) != null
