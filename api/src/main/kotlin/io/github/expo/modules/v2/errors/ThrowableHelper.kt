package io.github.expo.modules.v2.errors

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.modules.v2.JavaScriptThrowable

@CalledFromNative(by = "expo-modules-v2/jni/JThrowableHelper.h")
object ThrowableHelper {
  fun codeOf(throwable: Throwable): String =
    if (throwable is JavaScriptThrowable) {
      throwable.code
    } else {
      throwable.javaClass.name
    }

  /** The error's `message`: the throwable's own, else its `toString()`. */
  fun messageOf(throwable: Throwable): String = throwable.message ?: throwable.toString()

  /** `[code, message, Kotlin stack]`, for the native side to build a JavaScript error from. */
  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JThrowableHelper.h")
  fun describe(throwable: Throwable): Array<String> =
    arrayOf(codeOf(throwable), messageOf(throwable), throwable.stackTraceToString())
}
