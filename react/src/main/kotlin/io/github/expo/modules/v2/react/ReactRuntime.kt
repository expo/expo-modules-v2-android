package io.github.expo.modules.v2.react

import com.facebook.react.bridge.ReactApplicationContext
import io.github.expo.kolibri.NativePointer
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.jsi.JavaScriptRuntime
import io.github.expo.modules.v2.modules.ModuleRegistry

/**
 * A [JavaScriptRuntime] attached to the `jsi::Runtime` React Native created.
 *
 * The desktop counterpart (`io.github.expo.modules.v2.testsupport.HermesRuntime`) creates a VM and owns it. Here
 * the host owns it, so this class borrows the address React Native exposes through
 * `ReactContext.javaScriptContextHolder` and never frees the runtime — only the C++ object that
 * wraps it.
 *
 * Construct it on React Native's JS thread: a `jsi::Runtime` is thread-affine and installing the
 * module host object touches the global object.
 *
 * @param context the app this runtime belongs to. Modules and shared objects reach its
 * [ReactApplicationContext] through the [io.github.expo.modules.v2.ExpoObject.reactContext]
 * extension.
 * @param ownsContext whether [close] closes [context] too.
 * @param globalName the global the modules are installed under. It defaults to `expoV2` rather than
 * `expo`, because in a React Native app `globalThis.expo` already belongs to `expo-modules-core` —
 * installing over it would take every classic Expo module down with it.
 */
class ReactRuntime private constructor(
  context: ReactExpoContext,
  ownsContext: Boolean,
  jsRuntimePointer: Long,
  moduleRegistry: ModuleRegistry,
  asyncContext: AsyncContext,
  globalName: String,
) : JavaScriptRuntime(
  moduleRegistry,
  asyncContext,
  NativePointer(nativeCreate(jsRuntimePointer, moduleRegistry, asyncContext, globalName)),
  context,
  ownsContext,
) {
  val reactContext: ReactApplicationContext = context.reactContext

  companion object {
    const val DEFAULT_GLOBAL_NAME = "expoV2"

    init {
      ExpoModulesV2React.ensureLoaded()
    }

    /**
     * Attaches a runtime to [reactContext] and returns it, or throws when React Native exposes no
     * runtime (which happens if this runs before the context is initialized).
     *
     * MUST run on the JS thread. The caller has to keep the returned handle reachable for as long
     * as JavaScript can reach the installed modules: the handle owns the native runtime through
     * kolibri's cleaner, so dropping it frees the C++ object out from under the host object.
     *
     * Pass [context] to share modules and shared objects with other runtimes of the same app; it
     * must wrap [reactContext], and it stays the caller's to close. When null, the runtime creates
     * a context of its own and closes it with itself.
     */
    fun attach(
      reactContext: ReactApplicationContext,
      moduleRegistry: ModuleRegistry = ModuleRegistry(),
      globalName: String = DEFAULT_GLOBAL_NAME,
      context: ReactExpoContext? = null,
    ): ReactRuntime {
      require(context == null || context.reactContext === reactContext) {
        "The ReactExpoContext wraps a different ReactApplicationContext than the one to attach to"
      }

      val holder = requireNotNull(reactContext.javaScriptContextHolder) {
        "ReactContext.javaScriptContextHolder is null — there is no jsi::Runtime to attach to"
      }
      val pointer = holder.get()
      require(pointer != 0L) { "ReactContext.javaScriptContextHolder holds a null runtime" }

      val scheduler = ReactJSScheduler(reactContext)
      val runtime = ReactRuntime(
        context = context ?: ReactExpoContext(reactContext),
        ownsContext = context == null,
        jsRuntimePointer = pointer,
        moduleRegistry = moduleRegistry,
        asyncContext = AsyncContext(scheduler),
        globalName = globalName,
      )
      // Only possible after the runtime exists, and required before the first async export settles.
      scheduler.drainMicrotasks = { runtime.drainMicrotasks() }
      return runtime
    }

    /**
     * Allocates the native runtime around the `jsi::Runtime` at [jsRuntimePointer] and returns the
     * address of its C++ object. It keeps global references to [registry] and [asyncContext], the
     * same way the Hermes runtime does.
     */
    @JvmStatic
    private external fun nativeCreate(
      jsRuntimePointer: Long,
      registry: ModuleRegistry,
      asyncContext: AsyncContext,
      globalName: String,
    ): Long
  }
}
