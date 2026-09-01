package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.testsupport.TestSupport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Fixture for kolibri's JNI array API: the externals are registered from native code
 * (`ExpoTestSupport.__kolibriArrayBindFixture`), with every JNI signature derived from the C++
 * parameter types (`[D`, `[I`, ...). See test-support/src/main/cpp/KolibriArrayCheck.cpp.
 */
private object KolibriArrayFixture {
  external fun sum(values: DoubleArray): Double

  external fun scale(values: DoubleArray, factor: Double): DoubleArray

  /** Writes `start`, `start + 1`, ... into the caller's array via a pinned view. */
  external fun fill(values: IntArray, start: Int)
}

/**
 * End-to-end coverage for kolibri's JNI array API (kolibri/array.h). The C++ side of these
 * checks lives in test-support/src/main/cpp/KolibriArrayCheck.cpp; this suite drives it through
 * the host-function hooks and exercises native methods with array signatures against real Kotlin
 * arrays.
 */
class KolibriArrayTest {
  companion object {
    init {
      // Wire kolibri's native bindings to the .dylib before the first native-backed class loads.
      ExpoHermes.ensureLoaded()
    }
  }

  @Test
  fun `the array api works against the live JVM`() {
    HermesRuntime().use { runtime ->
      TestSupport.install(runtime)
      assertEquals("ok", runtime.evaluate("ExpoTestSupport.__kolibriArrayCheck()").getString())
    }
  }

  @Test
  fun `native methods take, return, and mutate primitive JNI arrays`() {
    HermesRuntime().use { runtime ->
      TestSupport.install(runtime)
      runtime.evaluate("ExpoTestSupport.__kolibriArrayBindFixture()")

      // In: a read-only pin over the caller's array.
      assertEquals(4.5, KolibriArrayFixture.sum(doubleArrayOf(1.5, 3.0)))
      assertEquals(0.0, KolibriArrayFixture.sum(doubleArrayOf()))

      // Out: a fresh array created from a C++ span.
      assertContentEquals(
        doubleArrayOf(3.0, 6.0),
        KolibriArrayFixture.scale(doubleArrayOf(1.0, 2.0), 3.0),
      )

      // In-place: writes through a mutable pin land in the caller's array.
      val values = IntArray(4)
      KolibriArrayFixture.fill(values, 10)
      assertContentEquals(intArrayOf(10, 11, 12, 13), values)
    }
  }
}
