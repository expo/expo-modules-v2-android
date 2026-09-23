package io.github.expo.modules.v2.compiler.fir

import org.jetbrains.kotlin.fir.expressions.FirCollectionLiteral
import org.jetbrains.kotlin.fir.expressions.FirExpression

/**
 * The elements of an `[a, b]` literal, or null when this is not one. Kotlin 2.3.20+:
 * `FirArrayLiteral` became [FirCollectionLiteral].
 */
internal fun FirExpression.collectionLiteralElements(): List<FirExpression>? =
  (this as? FirCollectionLiteral)?.argumentList?.arguments
