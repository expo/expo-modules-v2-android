package io.github.expo.modules.v2.jsi

import io.github.expo.kolibri.NativeMethod
import io.github.expo.kolibri.NativeObject
import io.github.expo.kolibri.NativePointer
import io.github.expo.modules.v2.ExpoContext
import io.github.expo.modules.v2.ExpoObject
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.modules.ModuleRegistry
import java.lang.ref.WeakReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * @param context the app this runtime belongs to. Runtimes that share one [ExpoContext] can share
 * module and shared object instances.
 * @param ownsContext whether [close] closes [context] too: true for a context the runtime created
 * for itself, false for one the caller passed in and still owns.
 */
abstract class JavaScriptRuntime protected constructor(
  val moduleRegistry: ModuleRegistry,
  val asyncContext: AsyncContext,
  pointer: NativePointer,
  /** The app this runtime belongs to. Every module and shared object it meets is bound to it. */
  val context: ExpoContext,
  private val ownsContext: Boolean,
) : NativeObject(pointer), AutoCloseable {

  /** The thread this runtime was constructed on, which is its JS thread. */
  private val jsThread: Thread = Thread.currentThread()

  /** Set by [close]. */
  @Volatile
  var isClosed: Boolean = false
    private set

  init {
    asyncContext.attach(pointer.value)

    // One runtime per thread is assumed: a second one on the same thread takes the slot over.
    CurrentRuntime.set(WeakReference(this))

    moduleRegistry.bind(context)
  }

  //@formatter:off
  @NativeMethod external fun evaluate(script: String, sourceURL: String = "<eval>"): JavaScriptValue
  @NativeMethod external fun global(): JavaScriptObject
  @NativeMethod external fun createObject(): JavaScriptObject

  /**
   * The JavaScript object that stands for [instance] in this runtime, or null if this runtime has
   * none yet: a module JavaScript has not read from `expo.modules`, or a shared object no function
   * has returned to it. Once it exists, it is the very object JavaScript holds.
   */
  @NativeMethod external fun jsObjectOf(instance: ExpoObject): JavaScriptObject?
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
    isClosed = true
    // Only the JS thread's slot can name this runtime. A close from any other thread leaves the
    // slot alone; [current] skips a closed runtime.
    if (Thread.currentThread() === jsThread && CurrentRuntime.raw()?.get() === this) {
      CurrentRuntime.set(null)
    }

    if (ownsContext) {
      context.close()
    }

    // Before destroy(), so no coroutine can still be handed a pointer that is about to go away.
    // The native destructor invalidates the context a second time for the cleaner's path.
    asyncContext.invalidate()
    destroy()
  }

  companion object {
    /**
     * The runtime whose JS thread is the calling thread, or null on any other thread. This is how
     * a [io.github.expo.modules.v2.SharedObject] built inside a JavaScript call finds its runtime.
     *
     * Assumes one runtime per thread, as in React Native. With several runtimes on one thread, it
     * names the one created last.
     */
    @JvmStatic
    val current: JavaScriptRuntime?
      get() = CurrentRuntime.get()
  }
}
