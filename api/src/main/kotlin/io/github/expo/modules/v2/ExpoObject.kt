package io.github.expo.modules.v2

import io.github.expo.modules.v2.events.Event
import io.github.expo.modules.v2.events.EventSupport
import java.util.concurrent.atomic.AtomicLong

abstract class ExpoObject {
  @JvmField
  internal val objectId: Long = nextObjectId.getAndIncrement()

  @JvmField
  internal var events: ArrayList<Event<*>>? = null

  protected fun <T> event(
    onStartObserving: (() -> Unit)? = null,
    onStopObserving: (() -> Unit)? = null,
  ): Event<T> = Event(this, onStartObserving, onStopObserving)

  private companion object {
    private val nextObjectId = AtomicLong(1)
  }
}
