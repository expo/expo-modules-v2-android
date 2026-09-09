package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@ExpoSharedObject
private class ConstructedCounter : SharedObject() {
  private var count: Int = 0

  @JS
  fun tick(): Int = ++count
}

@ExpoModule(name = "ConstructedModule")
private class ConstructedModule : Module() {
  @JS
  fun answer(): Int = 42
}

/** A plain Kotlin object with the same shape and no Expo base class, as the floor. */
private class PlainCounter {
  private var count: Int = 0

  fun tick(): Int = ++count
}

/**
 * What constructing a module or a shared object costs on the Kotlin side alone, with no runtime
 * and no JavaScript involved. This is the price every instance pays whether or not it ever
 * crosses into JavaScript, so a design that moves work into the constructor shows up here.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class ObjectConstructionBenchmark {
  @Setup
  fun setUp() {
    NativeLibraries.load()
  }

  @Benchmark
  fun plainObject(): Any = PlainCounter()

  @Benchmark
  fun sharedObject(): SharedObject = ConstructedCounter()

  @Benchmark
  fun module(): Module = ConstructedModule()
}
