package io.github.expo.modules.v2.events

import io.github.expo.modules.v2.ExpoObject
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.types.TypeDescriptor

/**
 * One event a module or shared object can emit to JavaScript.
 *
 * Created with [ExpoObject.event] inside the owning class, and bound to its JavaScript name and
 * payload type by the compiler plugin (for an `@Event` property) or by [EventSupport.bind].
 *
 * [emit] may be called from any thread. The payload is converted once per runtime that has a
 * listener, on that runtime's JS thread, so it must not change after it was handed over.
 * Delivery is synchronous when the caller already runs on that JS thread, and posted through the
 * runtime's scheduler otherwise.
 *
 * [onStartObserving] runs when the first listener in any runtime subscribes; [onStopObserving]
 * when the last one in every runtime unsubscribes, when a runtime that observes is torn down (its
 * listeners die with it, and it reports each one), or when a shared object is released. Both run
 * on the thread that caused the transition, normally that runtime's JS thread.
 */
class Event<T> internal constructor(
  internal val owner: ExpoObject,
  private val onStartObserving: (() -> Unit)?,
  private val onStopObserving: (() -> Unit)?,
) {
  internal var jsName: String? = null
  internal var descriptor: TypeDescriptor? = null
  internal var useBuffer: Boolean = false

  @JvmField
  internal var index: Int = -1

  private val observers = LinkedHashSet<AsyncContext>()

  /**
   * [observers] as an array, rebuilt under the lock whenever the set changes and read without it
   * by [emit]. Observing changes rarely; emitting is the hot path, and it must neither lock nor
   * allocate a snapshot per call.
   */
  @Volatile
  private var observerSnapshot: Array<AsyncContext> = NO_OBSERVERS

  /** The name JavaScript subscribes to. */
  val name: String
    get() = jsName ?: unbound()

  /** Whether any runtime currently listens to this event. */
  val isObserved: Boolean
    get() = synchronized(EventSupport.lock) { observers.isNotEmpty() }

  fun emit(payload: T) {
    val name = name
    val descriptor = requireNotNull(descriptor)
    val targets = observerSnapshot
    if (targets.isEmpty()) {
      return
    }

    for (context in targets) {
      context.scheduler.postOrExecuteIfOnJsThread {
        EventSupport.deliver(context, this, name, descriptor, useBuffer, payload)
      }
    }
  }

  operator fun invoke(payload: T) = emit(payload)

  internal fun attach(context: AsyncContext) {
    val started = synchronized(EventSupport.lock) {
      val added = observers.add(context)
      if (added) {
        observerSnapshot = if (observers.size == 1) {
          arrayOf(context)
        } else {
          observers.toTypedArray()
        }
      }
      added && observers.size == 1
    }
    if (started) {
      onStartObserving?.invoke()
    }
  }

  internal fun detach(context: AsyncContext) {
    val stopped = synchronized(EventSupport.lock) {
      val removed = observers.remove(context)
      if (removed) {
        observerSnapshot = if (observers.isEmpty()) {
          NO_OBSERVERS
        } else {
          observers.toTypedArray()
        }
      }
      removed && observers.isEmpty()
    }
    if (stopped) {
      onStopObserving?.invoke()
    }
  }

  internal fun detachAll() {
    val stopped = synchronized(EventSupport.lock) {
      if (observers.isEmpty()) {
        false
      } else {
        observers.clear()
        observerSnapshot = NO_OBSERVERS
        true
      }
    }
    if (stopped) {
      onStopObserving?.invoke()
    }
  }

  private fun unbound(): Nothing = throw IllegalStateException(
    "This event of ${owner.javaClass.name} is not bound to a JavaScript name - declare it as an " +
      "@Event property, or bind it with EventSupport.bind(event, name, type, useBuffer)",
  )

  override fun toString(): String = "Event(${jsName ?: "<unbound>"} of ${owner.javaClass.simpleName})"

  private companion object {
    val NO_OBSERVERS: Array<AsyncContext> = emptyArray()
  }
}
