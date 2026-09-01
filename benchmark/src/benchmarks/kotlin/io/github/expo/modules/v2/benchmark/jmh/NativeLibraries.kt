package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.testsupport.ExpoHermes

/**
 * Loads the native libraries the JMH suite needs, from a JMH forked JVM.
 *
 * JMH runs every trial in a forked JVM, so the libraries are loaded again in each fork. The
 * `java.library.path` that the Gradle task sets on the launcher JVM is inherited by the forks
 * (JMH passes the parent's JVM arguments to the child unless `@Fork(jvmArgs = ...)` overrides
 * them), so plain `System.loadLibrary` resolution works here exactly as it does in the
 * `main()`-style harnesses.
 */
object NativeLibraries {
  fun load() {
    ExpoHermes.ensureLoaded()
  }
}
