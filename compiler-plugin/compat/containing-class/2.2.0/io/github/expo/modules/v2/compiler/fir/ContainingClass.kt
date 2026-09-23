package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol

// Kotlin 2.2.0 - 2.2.21: the helpers live with the checkers.

/** The class this declaration is a member of, or null for a top-level or local one. */
internal fun FirBasedSymbol<*>.containingClass(): FirClassLikeSymbol<*>? = getContainingClassSymbol()

/** The class this declaration is a member of, or null for a top-level or local one. */
internal fun FirDeclaration.containingClass(): FirClassLikeSymbol<*>? = getContainingClassSymbol()
