package io.github.expo.modules.v2.jsi

import java.lang.ref.WeakReference

/**
 * The per-thread slot behind [JavaScriptRuntime.current].
 *
 * It lives outside [JavaScriptRuntime] because that class loads the native library when it is
 * initialized, and a [io.github.expo.modules.v2.SharedObject] reads this slot in its constructor,
 * which must keep working without it. Weak, so a runtime dropped without `close()` can still be
 * collected.
 */
internal object CurrentRuntime {
  private val slot = ThreadLocal<WeakReference<JavaScriptRuntime>?>()

  fun get(): JavaScriptRuntime? = slot.get()?.get()?.takeUnless { it.isClosed }

  fun raw(): WeakReference<JavaScriptRuntime>? = slot.get()

  fun set(runtime: WeakReference<JavaScriptRuntime>?) = slot.set(runtime)
}
