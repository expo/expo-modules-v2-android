package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.expressions.FirArrayLiteral
import org.jetbrains.kotlin.fir.expressions.FirExpression

/**
 * The elements of an `[a, b]` literal, or null when this is not one. Kotlin 2.2.0 - 2.3.10:
 * [FirArrayLiteral].
 */
internal fun FirExpression.collectionLiteralElements(): List<FirExpression>? =
  (this as? FirArrayLiteral)?.argumentList?.arguments
