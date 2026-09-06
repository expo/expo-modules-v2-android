package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.testsupport.HermesRuntime

/** Keeps string-oriented bridge tests concise while [HermesRuntime.evaluate] returns a value handle. */
internal fun HermesRuntime.evaluateAsString(script: String, sourceURL: String = "<eval>"): String {
  val value = evaluate(script, sourceURL)
  return when {
    value.isUndefined() -> "undefined"
    value.isNull() -> "null"
    value.isBool() -> value.getBool().toString()
    value.isNumber() -> {
      val number = value.getDouble()
      when {
        number.isNaN() -> "NaN"
        number == Double.POSITIVE_INFINITY -> "Infinity"
        number == Double.NEGATIVE_INFINITY -> "-Infinity"
        number == 0.0 -> "0"
        number % 1.0 == 0.0 && kotlin.math.abs(number) < 1e21 ->
          java.math.BigDecimal.valueOf(number).toBigInteger().toString()
        else -> number.toString().replace('E', 'e').let { result ->
          if ("e" in result && "e-" !in result) result.replace("e", "e+") else result
        }
      }
    }
    value.isString() -> value.getString()
    else -> error("Expected a primitive JavaScript result, got ${value.kind()}")
  }
}
