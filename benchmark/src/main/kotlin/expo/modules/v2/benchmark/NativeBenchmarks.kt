package expo.modules.v2.benchmark

import expo.modules.v2.core.ExpoModulesV2

/**
 * Benchmark-only entry points into this module's native library (`libexpo-benchmark`). Not
 * product surface.
 *
 * The bridge library must be loaded first: `libexpo-benchmark` links against `libexpo-kolibri`
 * (the one process-wide kolibri copy) and against the `libfbjni` shipped by `:hermes`, both
 * resolved through the library's rpath once they are on `java.library.path`.
 */
object NativeBenchmarks {
  init {
    ExpoModulesV2.load()
    System.loadLibrary("expo-benchmark")
  }

  /**
   * Runs the native JavaClass-vs-fbjni method-dispatch micro-benchmark and returns its ns/op
   * report.
   */
  @JvmStatic
  external fun runJniCallBenchmark(): String
}
