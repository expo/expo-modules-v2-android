package io.github.expo.modules.v2.events

import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.types.TypeDescriptor
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What an [Event] does before any runtime is involved: binding, and emitting into the void. The
 * observation bookkeeping needs an `AsyncContext`, which loads the native library, so it is pinned
 * by `:test-app`'s `EventEmitterTest` instead.
 */
class EventTest {
  private class Watcher : Module() {
    var starts = 0
    var stops = 0

    val onChanged = EventSupport.bind(
      event<Int>(onStartObserving = { starts++ }, onStopObserving = { stops++ }),
      "changed",
      TypeDescriptor.Int,
    )

    val unbound = event<Int>()
  }

  @Test
  fun `emitting without an observer is a no-op`() {
    val watcher = Watcher()
    watcher.onChanged.emit(1)
    watcher.onChanged(2)
  }

  @Test
  fun `an unbound event cannot be emitted and says how to bind it`() {
    val error = assertFailsWith<IllegalStateException> { Watcher().unbound.emit(1) }
    assertTrue("@Event" in error.message!!, error.message)
    assertTrue("EventSupport.bind" in error.message!!, error.message)
  }

  @Test
  fun `an event binds once, under one name per owner`() {
    val watcher = Watcher()
    assertFailsWith<IllegalStateException> {
      EventSupport.bind(watcher.onChanged, "again", TypeDescriptor.Int)
    }
    assertFailsWith<IllegalArgumentException> {
      EventSupport.bind(watcher.unbound, "changed", TypeDescriptor.Int)
    }
  }
}
