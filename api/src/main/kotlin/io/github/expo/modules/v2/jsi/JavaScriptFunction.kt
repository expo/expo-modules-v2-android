package io.github.expo.modules.v2.jsi

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.NativeMethod
import io.github.expo.kolibri.NativeObject
import io.github.expo.kolibri.NativePointer

/**
 * A live JavaScript function, together with the signature it was declared with.
 *
 * It is the bridge value of a lambda parameter. User code never sees it: [FunctionConverter] wraps
 * it in a Kotlin function object that converts the values and watches the thread.
 */
@CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h")
class JavaScriptFunction @CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h") private constructor(
  pointer: Long,
) : NativeObject(NativePointer(pointer)) {

  /**
   * Calls the function with [args] - already in their bridge form - and reports how it went to
   * [callback], before this returns or, for a promise, later from the JS thread.
   *
   * Must run on the JS thread. With [awaitPromise], a promise the function returns is waited for;
   * without it, whatever the function returns is the result.
   */
  @NativeMethod external fun call(args: Array<Any?>, callback: JavaScriptFunctionCallback, awaitPromise: Boolean)
}

/** Where [JavaScriptFunction.call] reports. Exactly one of the two is called, once. */
@CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h")
abstract class JavaScriptFunctionCallback {
  /** [value] is already in the bridge form of the declared return type. */
  @CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h")
  abstract fun resolve(value: Any?)

  @CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptFunction.h")
  abstract fun reject(code: String, message: String, stack: String?)
}
