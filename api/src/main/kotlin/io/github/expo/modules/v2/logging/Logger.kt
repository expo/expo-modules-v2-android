package io.github.expo.modules.v2.logging

interface Logger {
  fun warn(message: String, throwable: Throwable? = null)

  fun error(message: String, throwable: Throwable? = null)
}
