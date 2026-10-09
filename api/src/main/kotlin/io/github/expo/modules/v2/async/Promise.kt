package io.github.expo.modules.v2.async

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

typealias Resolve<T> = (T) -> Unit
typealias Reject = (Throwable) -> Unit

/**
 * A value a synchronous `@JS` function hands back before it exists, so its body does not have to
 * be `suspend`.
 *
 * ```
 * @JS
 * fun load(url: String): Promise<String> {
 *   val promise = Promise<String>()
 *   executor.execute { promise.resolve(fetch(url)) }
 *   return promise
 * }
 *
 * // The same, the way JavaScript's `new Promise` spells it.
 * @JS
 * fun load(url: String): Promise<String> = Promise { resolve, reject ->
 *   executor.execute {
 *     try { resolve(fetch(url)) } catch (e: IOException) { reject(e) }
 *   }
 * }
 *
 * // Already settled, like `Promise.resolve(...)` and `Promise.reject(...)`.
 * @JS
 * fun cached(key: String): Promise<String> =
 *   cache[key]?.let { Promise.resolve(it) } ?: Promise.reject(NoSuchElementException(key))
 * ```
 *
 * JavaScript gets a real promise as soon as the function returns, and it settles with whatever this
 * one settles with. [resolve] and [reject] may be called from any thread, before or after the
 * function returns, and only the first call counts. A promise that is never settled leaves
 * JavaScript waiting forever.
 */
class Promise<T>() {

  /** Written once, by the first [resolve] or [reject]. */
  @Volatile
  private var _state: State = State.Pending

  @Volatile
  private var _subscribers: PromiseHandle? = null

  constructor(executor: (resolve: Resolve<T>, reject: Reject) -> Unit) : this() {
    try {
      executor(
        { value -> resolve(value) },
        { throwable -> reject(throwable) }
      )
    } catch (throwable: Throwable) {
      reject(throwable)
    }
  }

  fun resolve(value: T) {
    settle(State.resolved(value))
  }

  fun reject(throwable: Throwable) {
    settle(State.rejected(throwable))
  }

  private fun settle(state: State) {
    if (OUTCOME.compareAndSet(this, State.Pending.raw, state.raw)) {
      notifySubscribers(state)
    }
  }

  internal fun subscribe(handle: PromiseHandle) {
    val outcome = _state
    if (outcome.isSettled) {
      outcome.deliverTo(handle)
      return
    }

    while (true) {
      val head = _subscribers
      handle.nextSubscriber = head
      if (SUBSCRIBERS.compareAndSet(this, head, handle)) {
        break
      }
    }

    // [settle] may have taken the list just before [handle] joined it, and then nothing else would
    // deliver to it.
    val settled = _state
    if (settled.isSettled) {
      notifySubscribers(settled)
    }
  }

  private fun notifySubscribers(state: State) {
    var subscriber = SUBSCRIBERS.getAndSet(this, null)
    while (subscriber != null) {
      val next = subscriber.nextSubscriber
      state.deliverTo(subscriber)
      subscriber = next
    }
  }

  @JvmInline
  private value class State private constructor(val raw: Any?) {
    val isSettled: Boolean
      get() = raw !== PENDING

    /** Settles [handle] with this outcome, which must be settled. */
    fun deliverTo(handle: PromiseHandle) {
      when (raw) {
        is Rejected -> handle.reject(raw.throwable)
        else -> handle.resolveFollowed(raw)
      }
    }

    private class Rejected(val throwable: Throwable)

    private object PENDING

    companion object {
      val Pending = State(PENDING)

      fun resolved(value: Any?) = State(value)

      fun rejected(throwable: Throwable) = State(Rejected(throwable))
    }
  }

  companion object {
    /**
     * A promise already resolved with [value].
     */
    fun <T> resolve(value: T): Promise<T> = settled(State.resolved(value))

    /** A promise already resolved with no value - JavaScript sees `undefined`. */
    fun resolve(): Promise<Unit> = settled(State.resolved(Unit))

    /** A promise already rejected with [throwable]. */
    fun <T> reject(throwable: Throwable): Promise<T> = settled(State.rejected(throwable))

    private fun <T> settled(state: State): Promise<T> =
      Promise<T>().apply { _state = state }

    private val OUTCOME: AtomicReferenceFieldUpdater<Promise<*>, Any?> =
      AtomicReferenceFieldUpdater.newUpdater(Promise::class.java, Any::class.java, "_state")

    private val SUBSCRIBERS: AtomicReferenceFieldUpdater<Promise<*>, PromiseHandle?> =
      AtomicReferenceFieldUpdater.newUpdater(Promise::class.java, PromiseHandle::class.java, "_subscribers")
  }
}

/** Resolves a promise that carries no value; JavaScript sees `undefined`. */
fun Promise<Unit>.resolve() {
  resolve(Unit)
}
