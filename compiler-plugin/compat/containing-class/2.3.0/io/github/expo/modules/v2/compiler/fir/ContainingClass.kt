package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol

// Kotlin 2.3.0+: the helpers moved from the checkers to fir.resolve.

/** The class this declaration is a member of, or null for a top-level or local one. */
internal fun FirBasedSymbol<*>.containingClass(): FirClassLikeSymbol<*>? = getContainingClassSymbol()

/** The class this declaration is a member of, or null for a top-level or local one. */
internal fun FirDeclaration.containingClass(): FirClassLikeSymbol<*>? = getContainingClassSymbol()
