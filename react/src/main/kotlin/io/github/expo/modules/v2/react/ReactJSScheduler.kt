package io.github.expo.modules.v2.react

import com.facebook.react.bridge.ReactContext
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.async.JSScheduler

/**
 * The [JSScheduler] for a runtime React Native owns.
 *
 * React Native already has a JS thread, so this implementation supplies only the two operations a
 * host-driven scheduler needs — [post] and [isOnJSThread] — and leaves `drainPending`/`awaitJob` at
 * their host-driven defaults. Nothing here drives a loop of its own.
 */
class ReactJSScheduler(private val reactContext: ReactContext) : JSScheduler {
  /**
   * Runs the engine's microtask queue, set by [ReactRuntime] once the runtime exists.
   *
   * Settling a promise only queues its `.then` callbacks; something has to run them. React Native
   * drains microtasks after each task its own scheduler executes, but a job posted straight onto the
   * JS message queue is not one of those — so without this, an `await` on a v2 async export would
   * wait forever on a promise that is already resolved.
   */
  @Volatile
  internal var drainMicrotasks: (() -> Unit)? = null

  /**
   * Set by [close] when the runtime goes away. A settle that loses its race with teardown must not
   * reach a `ReactContext` that is being torn down itself.
   */
  @Volatile
  private var closed = false

  override val isOnJSThread: Boolean
    get() = reactContext.isOnJSQueueThread

  override fun post(job: Runnable) {
    if (closed) {
      return
    }
    // Returns false once the JS queue is gone; a dropped settle is not an error.
    reactContext.runOnJSQueueThread {
      job.run()
      // After the job, never inside it: the settle writes through the shared binary buffer, and a
      // `.then` callback may call straight back into another export.
      drainMicrotasks?.invoke()
    }
  }

  override fun close() {
    closed = true
    drainMicrotasks = null
  }
}
