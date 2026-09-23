package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.name.Name

/** A `String` annotation argument. Kotlin 2.2.0 - 2.3.21: the lookup takes the session. */
internal fun FirAnnotation.stringArgument(name: Name, session: FirSession): String? =
  getStringArgument(name, session)
