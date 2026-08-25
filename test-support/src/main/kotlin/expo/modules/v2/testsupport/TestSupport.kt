package expo.modules.v2.testsupport

import expo.modules.v2.core.ExpoModulesV2
import expo.modules.v2.jsi.JavaScriptRuntime

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

  @JvmStatic
  private external fun nativeInstall(runtimePointer: Long)
}
