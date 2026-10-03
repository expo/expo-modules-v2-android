package io.github.expo.modules.v2.logging

import android.util.Log

internal object AndroidLogger : Logger {
  private const val TAG = "ExpoModulesV2"

  override fun warn(message: String, throwable: Throwable?) {
    Log.w(TAG, message, throwable)
  }

  override fun error(message: String, throwable: Throwable?) {
    Log.e(TAG, message, throwable)
  }
}

/** The logger of this platform. */
internal val platformLogger: Logger = AndroidLogger
