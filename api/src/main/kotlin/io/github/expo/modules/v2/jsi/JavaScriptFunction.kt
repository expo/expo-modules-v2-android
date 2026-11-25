package io.github.expo.modules.v2.jsi

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.NativeMethod
import io.github.expo.kolibri.NativeObject
import io.github.expo.kolibri.NativePointer
import io.github.expo.modules.v2.async.AsyncContext

@CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h")
class JavaScriptFunction @CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h") private constructor(
  pointer: Long,
  asyncContext: Any?,
) : NativeObject(NativePointer(pointer)) {
  internal val asyncContext: AsyncContext? = asyncContext as AsyncContext?

  @NativeMethod
  external fun call(args: Array<Any?>, callback: JavaScriptFunctionCallback, settleNow: Boolean)
}

@CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h")
abstract class JavaScriptFunctionCallback {
  @CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h")
  abstract fun resolve(value: Any?)

  @CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h")
  abstract fun reject(code: String, message: String, stack: String?)
}
