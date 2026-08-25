package expo.modules.v2.compiler.fir

import expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol

class RecordPredicates(session: FirSession) : FirExtensionSessionComponent(session) {
  val predicate: DeclarationPredicate = DeclarationPredicate.create {
    annotated(Identifiers.FqNames.RECORD_ANNOTATION)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }
}

val FirSession.recordPredicates: RecordPredicates by FirSession.sessionComponentAccessor<RecordPredicates>()

fun FirClassSymbol<*>.isRecord(session: FirSession): Boolean =
  session.predicateBasedProvider.matches(session.recordPredicates.predicate, this)

