package io.github.expo.modules.v2.async

import io.github.expo.modules.v2.async.Promise.Companion.SETTLED
import io.github.expo.modules.v2.types.TypeDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater

class Promise internal constructor(
  private val id: Long,
  private val context: AsyncContext,
) {
  /**
   * Updated by the [SETTLED]
   */
  @Suppress("PropertyName")
  @Volatile
  @JvmField
  internal var _settled: Int = 0

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

  fun resolve(value: Any?, type: TypeDescriptor, buffered: Boolean) {
    if (!settled()) {
      return
    }
    context.postSettle { context.resolveOnJSThread(id, value, type, buffered) }
  }

  fun reject(throwable: Throwable) {
    if (!settled()) {
      return
    }

    val code = if (throwable is CancellationException) {
      CANCELLED_CODE
    } else {
      throwable.javaClass.name
    }
    val message = throwable.message ?: throwable.toString()
    val stack = throwable.stackTraceToString()

    context.postSettle { context.rejectOnJSThread(id, code, message, stack) }
  }

  private companion object {
    const val CANCELLED_CODE = "ERR_CANCELED"

    private val SETTLED: AtomicIntegerFieldUpdater<Promise> =
      AtomicIntegerFieldUpdater.newUpdater(Promise::class.java, "_settled")
  }
}
