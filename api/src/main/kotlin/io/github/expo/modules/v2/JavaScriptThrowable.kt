package io.github.expo.modules.v2

abstract class JavaScriptThrowable(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause) {
  open val code: String
    get() = ""
}
