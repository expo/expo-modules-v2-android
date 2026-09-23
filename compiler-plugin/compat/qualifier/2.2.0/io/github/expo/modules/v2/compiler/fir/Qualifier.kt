package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol

/** The class a qualifier such as `Foo` in `Foo::class` names. Kotlin 2.2.0 - 2.4.10: `symbol`. */
internal val FirResolvedQualifier.classSymbol: FirClassLikeSymbol<*>?
  get() = symbol
