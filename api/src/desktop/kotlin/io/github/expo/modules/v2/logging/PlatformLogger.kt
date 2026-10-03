package io.github.expo.modules.v2.logging

internal object SystemErrLogger : Logger {
  override fun warn(message: String, throwable: Throwable?) = print(message, throwable)

  override fun error(message: String, throwable: Throwable?) = print(message, throwable)

  private fun print(message: String, throwable: Throwable?) {
    System.err.println("expo-modules-v2: $message")
    throwable?.printStackTrace()
  }
}

/** The logger of this platform. */
internal val platformLogger: Logger = SystemErrLogger
