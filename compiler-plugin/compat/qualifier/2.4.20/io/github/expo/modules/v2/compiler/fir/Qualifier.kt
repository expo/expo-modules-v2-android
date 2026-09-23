package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol

/**
 * The class a qualifier such as `Foo` in `Foo::class` names. Kotlin 2.4.20+: `symbol` was renamed
 * to `qualifierSymbol`.
 */
internal val FirResolvedQualifier.classSymbol: FirClassLikeSymbol<*>?
  get() = qualifierSymbol
