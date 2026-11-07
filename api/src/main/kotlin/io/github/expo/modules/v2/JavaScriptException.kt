package io.github.expo.modules.v2

/**
 * What Kotlin sees when a JavaScript function it called throws, or returns a promise that rejects.
 *
 * Left uncaught, it travels on to the JavaScript caller of the export as an error with the same
 * [message] and [code].
 *
 * @param code the `code` property of the error JavaScript threw, or empty when it had none.
 * @param jsStack the `stack` of that error, when it had one.
 */
class JavaScriptException(
  message: String,
  override val code: String = "",
  val jsStack: String? = null,
) : JavaScriptThrowable(message)
