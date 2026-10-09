package io.github.expo.modules.v2.async

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.modules.v2.async.PromiseHandle.Companion.SETTLED
import io.github.expo.modules.v2.errors.ThrowableHelper
import io.github.expo.modules.v2.types.TypeDescriptor
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@CalledFromNative(by = "expo-modules-v2/jni/JAsyncContext.h")
class PromiseHandle internal constructor(
  private val id: Long,
  private val context: AsyncContext,
) : Runnable {
  /**
   * Updated by the [SETTLED]
   */
  @Suppress("PropertyName")
  @Volatile
  @JvmField
  internal var _settled: Int = 0

  /**
   * The next handle subscribed to the same [Promise]. The list belongs to that promise: it links a
   * handle in before publishing it, and walks the list once it settles.
   */
  @JvmField
  internal var nextSubscriber: PromiseHandle? = null

  private var value: Any? = null
  private var type: TypeDescriptor? = null
  private var buffered: Boolean = false
  private var rejected: Boolean = false

  // TODO(@lukmccall): we can probably assume that promise is not going to be used from only one thread at time
  @Suppress("NOTHING_TO_INLINE")
  private inline fun settled(): Boolean {
    return SETTLED.compareAndSet(this, 0, 1)
  }

  /**
   * Runs [block] as the export's body and settles this promise with whatever it produces.
   *
   * [Dispatchers.Unconfined] starts the body inline on the calling JS thread, so an export that
   * never actually suspends costs no thread hop. After a real suspension the body continues on
   * whichever thread resumed it - settling stays correct because [resolve] posts, but a JSI handle
   * would not, which is why a `suspend` export may not take or return one.
   *
   * Called by generated code
   */
  fun launch(
    type: TypeDescriptor,
    buffered: Boolean,
    block: suspend () -> Any?
  ): Job {
    return context.inlineWindow {
      exportScope.launch {
        try {
          resolve(block(), type, buffered)
        } catch (cancellation: CancellationException) {
          // Leaving it pending would hang every `await` on the JS side, so a cancelled body is a
          // rejection like any other. Rethrown so the scope still sees the cancellation.
          reject(cancellation)
          throw cancellation
        } catch (throwable: Throwable) {
          reject(throwable)
        }
      }
    }
  }

  /**
   * Settles this promise with whatever [promise] settles with - the async half of an export that
   * returns a [Promise] instead of suspending.
   *
   * The body has already returned by now. When it settled [promise] before returning, this settles
   * inside the call, the same as a `suspend` body that never suspends.
   *
   * Called by generated code.
   */
  fun subscribeTo(promise: Promise<*>, type: TypeDescriptor, buffered: Boolean) {
    this.type = type
    this.buffered = buffered
    context.inlineWindow {
      promise.subscribe(this@PromiseHandle)
    }
  }

  fun resolve(value: Any?, type: TypeDescriptor, buffered: Boolean) {
    if (!settled()) {
      return
    }
    this.value = value
    this.type = type
    this.buffered = buffered
    context.postSettle(this)
  }

  /** Resolves with the result type [subscribeTo] recorded. */
  internal fun resolveFollowed(value: Any?) {
    if (!settled()) {
      return
    }
    this.value = value
    context.postSettle(this)
  }

  fun reject(throwable: Throwable) {
    if (!settled()) {
      return
    }

    // Described here, on the settling thread, so the JS thread only crosses the result over.
    val code = if (throwable is CancellationException) {
      CANCELLED_CODE
    } else {
      ThrowableHelper.codeOf(throwable)
    }
    value = arrayOf(code, ThrowableHelper.messageOf(throwable), throwable.stackTraceToString())
    rejected = true
    context.postSettle(this)
  }

  /** Hands the outcome to JavaScript. Runs on the JS thread, once, after the handle settled. */
  override fun run() {
    if (rejected) {
      @Suppress("UNCHECKED_CAST")
      val description = value as Array<String>
      context.rejectOnJSThread(id, description[0], description[1], description[2])
    } else {
      context.resolveOnJSThread(id, value, checkNotNull(type), buffered)
    }
  }

  private companion object {
    const val CANCELLED_CODE = "ERR_CANCELED"

    private val SETTLED: AtomicIntegerFieldUpdater<PromiseHandle> =
      AtomicIntegerFieldUpdater.newUpdater(PromiseHandle::class.java, "_settled")
  }
}
