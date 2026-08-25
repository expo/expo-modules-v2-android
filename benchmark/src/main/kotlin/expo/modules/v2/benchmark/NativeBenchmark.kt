package expo.modules.v2.benchmark

import expo.modules.v2.testsupport.ExpoHermes

/**
 * The benchmarks whose measurement happens inside C++.
 *
 * These stay `main()` harnesses instead of moving to JMH: the native hooks run their own timing
 * loops and return a finished report, so a JMH wrapper would measure the one JNI call that starts
 * them, not the work being compared. Everything measured from the JVM side lives in
 * `src/benchmarks/kotlin` and runs under `./gradlew :benchmark:benchmarksBenchmark`.
 *
 * - `runJniCallBenchmark` — kolibri's JavaClass tokens vs fbjni for Java method dispatch.
 */
fun main() {
  ExpoHermes.ensureLoaded()

  println(NativeBenchmarks.runJniCallBenchmark())
}
