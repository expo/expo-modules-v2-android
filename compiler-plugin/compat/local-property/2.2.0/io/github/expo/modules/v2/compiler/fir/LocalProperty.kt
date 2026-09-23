package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.declarations.FirProperty

/** Whether this is a local variable. Kotlin 2.2.0 - 2.2.10: a flag on the declaration. */
internal val FirProperty.isLocalProperty: Boolean
  get() = isLocal
