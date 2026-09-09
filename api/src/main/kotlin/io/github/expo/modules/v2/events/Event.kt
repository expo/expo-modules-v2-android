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

  /** The runtimes with at least one listener for this event. Guarded by [EventSupport.lock]. */
  private val observers = LinkedHashSet<AsyncContext>()

  /** The name JavaScript subscribes to. */
  val name: String
    get() = jsName ?: unbound()

  /** Whether any runtime currently listens to this event. */
  val isObserved: Boolean
    get() = synchronized(EventSupport.lock) { observers.isNotEmpty() }

  fun emit(payload: T) {
    val name = name
    val descriptor = requireNotNull(descriptor)
    val targets = synchronized(EventSupport.lock) {
      if (observers.isEmpty()) {
        return
      }
      observers.toTypedArray()
    }

    for (context in targets) {
      if (context.scheduler.isOnJSThread) {
        EventSupport.deliver(context, owner, name, descriptor, useBuffer, payload)
      } else {
        context.scheduler.post {
          EventSupport.deliver(context, owner, name, descriptor, useBuffer, payload)
        }
      }
    }
  }

  operator fun invoke(payload: T) = emit(payload)

  internal fun attach(context: AsyncContext) {
    val started = synchronized(EventSupport.lock) {
      observers.add(context) && observers.size == 1
    }
    if (started) {
      onStartObserving?.invoke()
    }
  }

  internal fun detach(context: AsyncContext) {
    val stopped = synchronized(EventSupport.lock) {
      observers.remove(context) && observers.isEmpty()
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
}
