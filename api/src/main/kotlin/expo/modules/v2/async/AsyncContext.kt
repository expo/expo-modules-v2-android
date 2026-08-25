package expo.modules.v2.async

import expo.modules.v2.args.Bridge
import expo.modules.v2.args.Trampoline
import expo.modules.v2.core.ExpoModulesV2
import expo.modules.v2.types.TypeDescriptor
import io.github.expo.kolibri.CalledFromNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus

class AsyncContext(val scheduler: JSScheduler = DefaultJSScheduler()) {
  val dispatcher: JSDispatcher = JSDispatcher(scheduler)

  val scope: CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("expo-modules-v2"))

  internal val exportScope: CoroutineScope = scope + Dispatchers.Unconfined

  @Volatile
  private var runtimePointer: Long = 0

  internal fun attach(pointer: Long) {
    runtimePointer = pointer
  }

  @CalledFromNative(by = "expo-modules-v2/jni/JAsyncContext.h")
  fun createPromise(id: Long): Promise = Promise(id, this)

  @CalledFromNative(by = "expo-modules-v2/jni/JAsyncContext.h")
  fun invalidate() {
    runtimePointer = 0
    scope.cancel(CancellationException("The JavaScript runtime was closed"))
    scheduler.close()
  }

  /**
   * The settle that belongs to the host function still on the stack, if it has one already.
   *
   * A single slot is enough: one window is one trampoline call is one promise, and a promise
   * settles once.
   */
  private var inlineSettle: Runnable? = null

  /**
   * The thread currently running a `suspend` trampoline, or null when none is.
   *
   * A body that never suspends settles on this thread while the trampoline is still on the stack.
   */
  @Volatile
  private var inlineThread: Thread? = null

  /**
   * Hands [job] to the JS thread, or holds it for [drainInlineSettles] when the JS thread is
   * already inside the very call it belongs to.
   */
  internal fun postSettle(job: Runnable) {
    if (inlineThread === Thread.currentThread() && inlineSettle == null) {
      inlineSettle = job
      return
    }
    scheduler.post(job)
  }

  internal inline fun <T> inlineWindow(body: AsyncContext.() -> T): T {
    return try {
      inlineThread = Thread.currentThread()
      body()
    } finally {
      inlineThread = null
    }
  }

  @CalledFromNative(by = "expo-modules-v2/jni/JAsyncContext.h")
  fun drainInlineSettles() {
    val job = inlineSettle ?: return
    // Cleared before it runs: a settle that throws must not leave the slot occupied for the
    // next call.
    inlineSettle = null
    job.run()
  }

  /** Runs on the JS thread. [buffered] mirrors the transport the export's return type declared. */
  internal fun resolveOnJSThread(
    id: Long,
    value: Any?,
    type: TypeDescriptor,
    buffered: Boolean,
  ) {
    val pointer = runtimePointer
    if (pointer == 0L) {
      return
    }

    try {
      if (buffered) {
        nativeResolveBuffered(pointer, id, Trampoline.writeResult(value, type))
      } else {
        nativeResolve(pointer, id, Bridge.toJni(value, type))
      }
    } catch (throwable: Throwable) {
      // The body succeeded but its result would not cross. JS must still see one settled promise,
      // so the conversion failure becomes the rejection.
      rejectOnJSThread(
        id,
        throwable.javaClass.name,
        throwable.message ?: throwable.toString(),
        throwable.stackTraceToString(),
      )
    }
  }

  /** Runs on the JS thread. */
  internal fun rejectOnJSThread(id: Long, code: String, message: String, stack: String?) {
    val pointer = runtimePointer
    if (pointer == 0L) {
      return
    }
    nativeReject(pointer, id, code, message, stack)
  }

  private companion object {
    init {
      ExpoModulesV2.load()
    }

    @JvmStatic
    private external fun nativeResolveBuffered(runtimePointer: Long, id: Long, payloadLength: Int)

    @JvmStatic
    private external fun nativeResolve(runtimePointer: Long, id: Long, value: Any?)

    @JvmStatic
    private external fun nativeReject(
      runtimePointer: Long,
      id: Long,
      code: String,
      message: String,
      stack: String?,
    )
  }
}
