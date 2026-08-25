package expo.modules.v2.compiler.fir

import expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol

class JSPredicates(session: FirSession): FirExtensionSessionComponent(session) {
  val predicate: DeclarationPredicate = DeclarationPredicate.create {
    annotated(Identifiers.FqNames.JS_ANNOTATION)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }
}

val FirSession.jsPredicates: JSPredicates by FirSession.sessionComponentAccessor<JSPredicates>()

fun FirClassSymbol<*>.hasJsAnnotation(session: FirSession): Boolean =
  session.predicateBasedProvider.matches(session.jsPredicates.predicate, this)

fun FirBasedSymbol<*>.hasJsAnnotation(session: FirSession): Boolean =
  session.predicateBasedProvider.matches(session.jsPredicates.predicate, this)
