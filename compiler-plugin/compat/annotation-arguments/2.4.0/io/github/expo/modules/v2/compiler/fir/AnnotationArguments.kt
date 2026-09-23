package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.name.Name

/** A `String` annotation argument. Kotlin 2.4.0+: the lookup no longer needs the session. */
@Suppress("UNUSED_PARAMETER")
internal fun FirAnnotation.stringArgument(name: Name, session: FirSession): String? =
  getStringArgument(name)
