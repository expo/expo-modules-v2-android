package io.github.expo.modules.v2

import io.github.expo.modules.v2.events.Event
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

abstract class ExpoObject {
  @JvmField
  internal val objectId: Long = nextObjectId.getAndIncrement()

  @JvmField
  internal var events: ArrayList<Event<*>>? = null

  @Volatile
  @JvmField
  internal var contextRef: WeakReference<ExpoContext>? = null

  /**
   * The context this object belongs to, or null when it has none yet or that context is closed.
   */
  val contextOrNull: ExpoContext?
    get() = contextRef?.get()?.takeUnless { it.isClosed }

  /**
   * The context this object belongs to. Throws when it has none yet or that context is closed.
   * */
  val context: ExpoContext
    get() {
      val ref = contextRef ?: error(
        "${javaClass.name} is not bound to an ExpoContext yet. A module is bound when it is " +
          "registered. A shared object made off the JS thread must be constructed with " +
          "SharedObject(context), or handed to JavaScript first",
      )
      return ref.get()?.takeUnless { it.isClosed }
        ?: error("The ExpoContext of ${javaClass.name} is closed")
    }

  /**
   * Binds this object to [context]. Rebinding to the same context does nothing, and a closed one
   * gives way to the new one.
   */
  @Synchronized
  internal fun bindContext(context: ExpoContext) {
    val bound = contextOrNull
    if (bound === context) {
      return
    }
    check(bound == null) {
      "${javaClass.name} already belongs to another ExpoContext. One instance can serve several " +
        "runtimes only when they share one context"
    }
    contextRef = WeakReference(context)
  }

  protected fun <T> event(
    onStartObserving: (() -> Unit)? = null,
    onStopObserving: (() -> Unit)? = null,
  ): Event<T> = Event(this, onStartObserving, onStopObserving)

  private companion object {
    private val nextObjectId = AtomicLong(1)
  }
}
