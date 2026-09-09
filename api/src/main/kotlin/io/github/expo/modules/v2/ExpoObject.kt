package io.github.expo.modules.v2

import io.github.expo.modules.v2.events.Event
import java.util.concurrent.atomic.AtomicLong

/**
 * Any Kotlin object JavaScript can hold a reference to: a [Module] or a [SharedObject].
 *
 * Carries the process-wide id the native side keys its instance tables by. One instance has one
 * id for its whole life, and each runtime maps that id to its own JavaScript object.
 */
abstract class ExpoObject {
  /** Assigned once here and only read by the native side, with a single JNI field read. */
  @JvmField
  internal val objectId: Long = nextObjectId.getAndIncrement()

  /**
   * The bound events of this instance by JavaScript name. Created by the first
   * [io.github.expo.modules.v2.events.EventSupport.bind], so an object without events carries
   * nothing.
   */
  @JvmField
  internal var events: MutableMap<String, Event<*>>? = null

  /**
   * Declares an event this object emits. Assign it to a `val` annotated `@Event`:
   *
   * ```
   * @Event
   * val onChanged = event<Change>(
   *   onStartObserving = ::startObservingChanges,
   *   onStopObserving = ::stopObservingChanges,
   * )
   * ```
   */
  protected fun <T> event(
    onStartObserving: (() -> Unit)? = null,
    onStopObserving: (() -> Unit)? = null,
  ): Event<T> = Event(this, onStartObserving, onStopObserving)

  private companion object {
    private val nextObjectId = AtomicLong(1)
  }
}
