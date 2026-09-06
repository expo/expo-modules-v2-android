package io.github.expo.modules.v2.testsupport

import io.github.expo.modules.v2.core.ExpoModulesV2
import io.github.expo.modules.v2.jsi.JavaScriptRuntime

/**
 * Test/benchmark-only entry point into this module's native library (`libexpo-test-support`). Not
 * product surface.
 *
 * [install] adds `globalThis.ExpoTestSupport` — the round-trip/smoke/benchmark host-function
 * suite (`__convertRoundTrip`, `__binaryNativeRoundTrip`, `__nativeStateChainSmoke`, ...) — to the given
 * runtime. Production runtimes never carry these; tests and benchmarks install them explicitly.
 *
 * The bridge library must be loaded first: `libexpo-test-support` links against `libexpo-kolibri`
 * (the one process-wide kolibri/jsi copy), resolved through the library's rpath once it is on
 * `java.library.path`.
 */
object TestSupport {
  init {
    ExpoModulesV2.load()
    System.loadLibrary("expo-test-support")
  }

  /** Installs `globalThis.ExpoTestSupport` into [runtime]. */
  fun install(runtime: JavaScriptRuntime) {
    nativeInstall(runtime.nativePointer)
  }

  /**
   * Moves the host object [expression] evaluates to in [from] into [to], as
   * `globalThis.<globalName>` — the same move `react-native-worklets` makes when a host object is
   * passed into a worklet, so what worklets can and cannot carry is testable without an app.
   *
   * Throws when [expression] is not a host object. A shared object's façade is a plain object, so
   * this is how the suite pins down that a façade does not cross a runtime boundary by reference.
   *
   * Both runtimes must be usable from the calling thread.
   */
  fun transplantHostObject(
    from: JavaScriptRuntime,
    to: JavaScriptRuntime,
    expression: String,
    globalName: String,
  ) {
    nativeTransplantHostObject(from.nativePointer, to.nativePointer, expression, globalName)
  }

  @JvmStatic
  private external fun nativeInstall(runtimePointer: Long)

  @JvmStatic
  private external fun nativeTransplantHostObject(
    fromPointer: Long,
    toPointer: Long,
    expression: String,
    globalName: String,
  )
}
