package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol

/** Whether this is a local variable. Kotlin 2.2.20+: local variables have their own symbol type. */
internal val FirProperty.isLocalProperty: Boolean
  get() = symbol is FirLocalPropertySymbol
