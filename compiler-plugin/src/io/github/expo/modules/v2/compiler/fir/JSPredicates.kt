package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

class JSPredicates(session: FirSession): FirExtensionSessionComponent(session) {
  val predicate: DeclarationPredicate = DeclarationPredicate.create {
    annotated(Identifiers.FqNames.JS_ANNOTATION)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }
}

val FirSession.jsPredicates: JSPredicates by FirSession.sessionComponentAccessor<JSPredicates>()

/** Whether this member is declared `@JS`. */
fun FirBasedSymbol<*>.hasJsAnnotation(session: FirSession): Boolean =
  session.predicateBasedProvider.matches(session.jsPredicates.predicate, this)
