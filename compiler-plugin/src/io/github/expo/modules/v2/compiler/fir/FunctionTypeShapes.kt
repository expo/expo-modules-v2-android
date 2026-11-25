package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isExtensionFunctionType
import org.jetbrains.kotlin.fir.types.type

private val FUNCTION_TYPE_NAME = Regex("kotlin/(coroutines/Suspend)?Function\\d+")

/** Whether this is a lambda type: `(A) -> R` or `suspend (A) -> R`. */
internal fun ConeKotlinType.isLambdaType(): Boolean =
  classId?.asString()?.let(FUNCTION_TYPE_NAME::matches) == true

/** Whether this type is a lambda type, or has one anywhere among its type arguments. */
internal fun ConeKotlinType.containsLambdaType(): Boolean =
  isLambdaType() || typeArguments.any { it.type?.containsLambdaType() == true }

/**
 * What is wrong with the lambdas a function or constructor takes or returns, if anything. A lambda
 * stands for a JavaScript function, which only crosses inbound and only on its own: as a parameter
 * that a plain call can reach, never nested in a list, a map or another lambda. The runtime cannot
 * build a lambda for anything else.
 */
internal fun functionTypeShape(declaration: FirFunction, session: FirSession): String? {
  for (parameter in declaration.valueParameters) {
    val type = parameter.returnTypeRef.coneType
    if (!type.isLambdaType()) {
      if (type.containsLambdaType()) {
        return "take a function type nested inside another type - a JavaScript function only " +
          "crosses as a parameter of its own"
      }
      continue
    }

    return when {
      type.isExtensionFunctionType(session) ->
        "take a function type with a receiver - JavaScript passes its arguments by position"

      type.typeArguments.size - 1 > Identifiers.MAX_ARGUMENTS ->
        "take a function type with more than ${Identifiers.MAX_ARGUMENTS} parameters"

      type.typeArguments.any { it.type?.containsLambdaType() == true } ->
        "take a function type that takes or returns another function type"

      else -> promiseInLambdaShape(type) ?: continue
    }
  }

  if (declaration.returnTypeRef.coneType.containsLambdaType()) {
    return "return a function type - a Kotlin lambda cannot be handed to JavaScript"
  }
  return null
}

/**
 * What is wrong with the `Promise` a lambda type mentions, if anything. A lambda may return one,
 * to get the JavaScript promise without waiting for it, but never take one: JavaScript cannot
 * receive a Kotlin promise.
 */
private fun promiseInLambdaShape(type: ConeKotlinType): String? {
  val arguments = type.typeArguments.mapNotNull { it.type }
  if (arguments.dropLast(1).any { it.classId == Identifiers.Classes.Promise }) {
    return "take a function type with a `Promise` parameter - JavaScript cannot receive one"
  }

  val returns = type.typeArguments.lastOrNull() ?: return null
  val returnType = returns.type ?: return null
  if (returnType.classId != Identifiers.Classes.Promise) {
    return null
  }
  return when {
    returnType.isMarkedNullable ->
      "take a function type that returns a nullable `Promise` - the lambda always hands one back"

    returnType.typeArguments.singleOrNull() is ConeStarProjection ->
      "take a function type that returns `Promise<*>` - the bridge needs the type the promise " +
        "resolves with"

    else -> null
  }
}
