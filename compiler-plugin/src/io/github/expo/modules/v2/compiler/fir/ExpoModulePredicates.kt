package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate

/**
 * Indexes every `@ExpoModule` class, so [ModuleHintGenerator] can enumerate them without walking
 * the module: declaring a hint per module needs the whole set up front.
 */
class ExpoModulePredicates(session: FirSession) : FirExtensionSessionComponent(session) {
  val predicate: LookupPredicate = LookupPredicate.create {
    annotated(Identifiers.FqNames.EXPO_MODULE_ANNOTATION)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }
}

val FirSession.expoModulePredicates: ExpoModulePredicates by
  FirSession.sessionComponentAccessor<ExpoModulePredicates>()
