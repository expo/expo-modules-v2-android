package io.github.expo.modules.v2.jsi

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.modules.v2.ExpoContext
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.core.ExpoModulesV2
import io.github.expo.modules.v2.modules.ModuleRegistry
import io.github.expo.kolibri.NativePointer

/**
 * A [JavaScriptRuntime] attached to a `jsi::Runtime` somebody else created. It serves the modules
 * of its context.
 */
@CalledFromNative(by = "expo-modules-v2/jsi/AttachedRuntime.h")
open class AttachedRuntime private constructor(
  jsRuntimePointer: Long,
  asyncContext: AsyncContext,
  engineName: String,
  globalName: String,
  context: ExpoContext,
  ownsContext: Boolean,
) : JavaScriptRuntime(
  asyncContext,
  NativePointer(
    nativeCreate(
      jsRuntimePointer = jsRuntimePointer,
      registry = context.moduleRegistry,
      asyncContext = asyncContext,
      engineName = engineName,
      globalName = globalName
    )
  ),
  context,
  ownsContext,
) {
  /**
   * @param context the context to share with other runtimes, which stays the caller's to close.
   * When null, the runtime creates a context of its own and closes it with itself.
   */
  constructor(
    jsRuntimePointer: Long,
    asyncContext: AsyncContext = AsyncContext(),
    engineName: String = DEFAULT_ENGINE_NAME,
    globalName: String = DEFAULT_GLOBAL_NAME,
    context: ExpoContext? = null,
  ) : this(
    jsRuntimePointer,
    asyncContext,
    engineName,
    globalName,
    context ?: ExpoContext(),
    ownsContext = context == null,
  )

  companion object {
    const val DEFAULT_ENGINE_NAME: String = "unknown"
    const val DEFAULT_GLOBAL_NAME: String = "expo"

    init {
      ExpoModulesV2.load()
    }

    /**
     * Allocates the native runtime around the `jsi::Runtime` at [jsRuntimePointer] and returns the
     * address of its C++ object. It keeps global references to [registry] and [asyncContext], and
     * queries them lazily, so the native side never reaches for this handle.
     */
    @JvmStatic
    private external fun nativeCreate(
      jsRuntimePointer: Long,
      registry: ModuleRegistry,
      asyncContext: AsyncContext,
      engineName: String,
      globalName: String,
    ): Long
  }
}
