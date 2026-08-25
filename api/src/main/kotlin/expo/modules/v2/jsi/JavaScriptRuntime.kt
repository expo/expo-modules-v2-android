package expo.modules.v2.jsi

import expo.modules.v2.async.AsyncContext
import expo.modules.v2.modules.ModuleRegistry
import io.github.expo.kolibri.NativeMethod
import io.github.expo.kolibri.NativeObject
import io.github.expo.kolibri.NativePointer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

abstract class JavaScriptRuntime protected constructor(
  val moduleRegistry: ModuleRegistry,
  val asyncContext: AsyncContext,
  pointer: NativePointer,
) : NativeObject(pointer), AutoCloseable {

  init {
    asyncContext.attach(pointer.value)
  }

  //@formatter:off
  @NativeMethod external fun evaluate(script: String, sourceURL: String = "<eval>"): JavaScriptValue
  @NativeMethod external fun global(): JavaScriptObject
  @NativeMethod external fun createObject(): JavaScriptObject
  //@formatter:on

  /**
   * Runs the engine's microtask queue until it is empty, so `.then` callbacks fire. Returns
   * whether the queue is now empty.
   */
  @NativeMethod external fun drainMicrotasks(): Boolean

  /**
   * Settles every promise whose coroutine has finished, then runs the `.then` callbacks that
   * settling queued. Returns whether anything ran.
   *
   * Jobs and microtasks alternate because each feeds the other: settling a promise queues its
   * `.then`, and a `.then` can call another async export whose body finishes immediately.
   *
   * MUST run on the JS thread, and MUST NOT be called from inside a host function — a settle
   * writes through the shared binary buffer, which a call in flight has already claimed.
   */
  fun drainJobs(): Boolean {
    var ran = false
    while (true) {
      val jobs = asyncContext.scheduler.drainPending()
      ran = ran || jobs > 0
      drainMicrotasks()
      if (jobs == 0) {
        return ran
      }
    }
  }

  /**
   * Drains until [until] holds, blocking between rounds while nothing is queued.
   *
   * This is the standalone driver: an app with a real JS thread runs its own loop and calls
   * [drainJobs] from it instead. Throws once [timeout] elapses, because a promise that never
   * settles is a bug worth surfacing rather than a hang.
   */
  fun runEventLoop(timeout: Duration = 5.seconds, until: () -> Boolean) {
    val started = TimeSource.Monotonic.markNow()
    while (true) {
      drainJobs()
      if (until()) {
        return
      }
      val remaining = timeout - started.elapsedNow()
      if (remaining <= Duration.ZERO) {
        error("runEventLoop timed out after $timeout with the condition still unmet")
      }
      asyncContext.scheduler.awaitJob(remaining.inWholeNanoseconds)
    }
  }

  override fun close() {
    // Before destroy(), so no coroutine can still be handed a pointer that is about to go away.
    // The native destructor invalidates the context a second time for the cleaner's path.
    asyncContext.invalidate()
    destroy()
  }
}
