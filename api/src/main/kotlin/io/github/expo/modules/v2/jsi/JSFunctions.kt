package io.github.expo.modules.v2.jsi

import io.github.expo.modules.v2.JavaScriptException
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.async.Promise
import io.github.expo.modules.v2.async.RuntimeCloseListener
import io.github.expo.modules.v2.converters.TypeConverter
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

internal class JSFunctionCaller(
  private val handle: JavaScriptFunction,
  private val params: Array<TypeConverter<Any>>,
  private val returns: TypeConverter<Any>,
  private val returnsUnit: Boolean,
  private val returnsPromise: Boolean,
  private val asyncContext: AsyncContext,
) {
  fun call(args: Array<Any?>): Any? = when {
    returnsPromise -> callForPromise(args)
    asyncContext.scheduler.isOnJSThread -> callOnJSThread(args)
    else -> callAndWait(args)
  }

  private fun callOnJSThread(args: Array<Any?>): Any? {
    var outcome: Result<Any?>? = null
    // TODO(@lukmccall): can we use binary buffer to pass data?
    handle.call(
      toBridge(args),
      Settlement { outcome = it },
      settleNow = true
    )
    return checkNotNull(outcome) { "The JavaScript function reported no outcome" }
      .getOrThrow()
  }

  private fun callAndWait(args: Array<Any?>): Any? {
    val done = CountDownLatch(1)
    var outcome: Result<Any?>? = null
    start(
      args,
      Settlement {
        outcome = it
        done.countDown()
      },
    )
    done.await()
    return checkNotNull(outcome).getOrThrow()
  }

  suspend fun callSuspending(args: Array<Any?>): Any? {
    if (returnsPromise) {
      return callForPromiseSuspending(args)
    }

    return suspendCancellableCoroutine { continuation ->
      val settlement = Settlement(continuation::resumeWith)
      continuation.invokeOnCancellation { asyncContext.removeCloseListener(settlement) }
      start(args, settlement)
    }
  }

  /** Blocks until the function returned - not until its promise settles - and hands that over. */
  private fun callForPromise(args: Array<Any?>): Promise<Any?> {
    val promise = Promise<Any?>()
    val returned = CountDownLatch(1)
    start(
      args,
      Settlement { outcome ->
        promise.settleWith(outcome)
        returned.countDown()
      },
      onReturned = returned::countDown,
    )
    returned.await()
    return promise
  }

  private suspend fun callForPromiseSuspending(args: Array<Any?>): Promise<Any?> {
    val promise = Promise<Any?>()
    suspendCancellableCoroutine { continuation ->
      val resumed = AtomicBoolean(false)
      val resume = {
        if (resumed.compareAndSet(false, true)) {
          continuation.resume(Unit)
        }
      }
      start(
        args,
        Settlement { outcome ->
          promise.settleWith(outcome)
          resume()
        },
        onReturned = resume,
      )
    }
    return promise
  }

  private fun start(args: Array<Any?>, settlement: Settlement, onReturned: () -> Unit = {}) {
    if (!asyncContext.addCloseListener(settlement)) {
      settlement.onRuntimeClosed()
      return
    }

    asyncContext.scheduler.postOrExecuteIfOnJsThread {
      try {
        handle.call(toBridge(args), settlement, settleNow = false)
      } catch (throwable: Throwable) {
        settlement.fail(throwable)
      } finally {
        onReturned()
      }
    }
  }

  private fun toBridge(args: Array<Any?>): Array<Any?> {
    check(args.size == params.size) {
      "The function takes ${params.size} argument(s), but was called with ${args.size}"
    }
    return Array(args.size) { params[it].toJni(args[it]) }
  }

  private fun fromBridge(value: Any?): Any? =
    if (returnsUnit) Unit else returns.fromJni(value)

  private fun Promise<Any?>.settleWith(outcome: Result<Any?>) {
    outcome.fold(::resolve, ::reject)
  }

  private inner class Settlement(
    private val onSettled: (Result<Any?>) -> Unit,
  ) : JavaScriptFunctionCallback(), RuntimeCloseListener {
    private val settled = AtomicBoolean(false)

    override fun resolve(value: Any?) {
      finish(runCatching { fromBridge(value) })
    }

    override fun reject(code: String, message: String, stack: String?) {
      finish(Result.failure(JavaScriptException(message, code, stack)))
    }

    override fun onRuntimeClosed() {
      fail(IllegalStateException("The JavaScript runtime this function belongs to was closed"))
    }

    fun fail(throwable: Throwable) {
      finish(Result.failure(throwable))
    }

    private fun finish(outcome: Result<Any?>) {
      if (!settled.compareAndSet(false, true)) {
        return
      }
      asyncContext.removeCloseListener(this)
      onSettled(outcome)
    }
  }
}

internal fun jsFunction(caller: JSFunctionCaller, paramCount: Int): Function<Any?> =
  when (paramCount) {
    0 -> JSFunction0(caller)
    1 -> JSFunction1(caller)
    2 -> JSFunction2(caller)
    3 -> JSFunction3(caller)
    4 -> JSFunction4(caller)
    5 -> JSFunction5(caller)
    6 -> JSFunction6(caller)
    7 -> JSFunction7(caller)
    8 -> JSFunction8(caller)
    else -> error("A function type cannot have $paramCount parameters")
  }

internal fun jsSuspendFunction(caller: JSFunctionCaller, paramCount: Int): Function<Any?> =
  when (paramCount) {
    0 -> JSSuspendFunction0(caller)
    1 -> JSSuspendFunction1(caller)
    2 -> JSSuspendFunction2(caller)
    3 -> JSSuspendFunction3(caller)
    4 -> JSSuspendFunction4(caller)
    5 -> JSSuspendFunction5(caller)
    6 -> JSSuspendFunction6(caller)
    7 -> JSSuspendFunction7(caller)
    8 -> JSSuspendFunction8(caller)
    else -> error("A function type cannot have $paramCount parameters")
  }

internal class JSFunction0(private val caller: JSFunctionCaller) : Function0<Any?> {
  override fun invoke(): Any? = caller.call(arrayOf())
}

internal class JSFunction1(private val caller: JSFunctionCaller) : Function1<Any?, Any?> {
  override fun invoke(p1: Any?): Any? = caller.call(arrayOf(p1))
}

internal class JSFunction2(private val caller: JSFunctionCaller) : Function2<Any?, Any?, Any?> {
  override fun invoke(p1: Any?, p2: Any?): Any? = caller.call(arrayOf(p1, p2))
}

internal class JSFunction3(
  private val caller: JSFunctionCaller,
) : Function3<Any?, Any?, Any?, Any?> {
  override fun invoke(p1: Any?, p2: Any?, p3: Any?): Any? = caller.call(arrayOf(p1, p2, p3))
}

internal class JSFunction4(
  private val caller: JSFunctionCaller,
) : Function4<Any?, Any?, Any?, Any?, Any?> {
  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?): Any? =
    caller.call(arrayOf(p1, p2, p3, p4))
}

internal class JSFunction5(
  private val caller: JSFunctionCaller,
) : Function5<Any?, Any?, Any?, Any?, Any?, Any?> {
  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?): Any? =
    caller.call(arrayOf(p1, p2, p3, p4, p5))
}

internal class JSFunction6(
  private val caller: JSFunctionCaller,
) : Function6<Any?, Any?, Any?, Any?, Any?, Any?, Any?> {
  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?): Any? =
    caller.call(arrayOf(p1, p2, p3, p4, p5, p6))
}

internal class JSFunction7(
  private val caller: JSFunctionCaller,
) : Function7<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?> {
  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, p7: Any?): Any? =
    caller.call(arrayOf(p1, p2, p3, p4, p5, p6, p7))
}

internal class JSFunction8(
  private val caller: JSFunctionCaller,
) : Function8<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?> {
  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, p7: Any?, p8: Any?,
  ): Any? = caller.call(arrayOf(p1, p2, p3, p4, p5, p6, p7, p8))
}

/** Starts the call as the usual suspend protocol: the value, or the marker that it suspended. */
private fun JSFunctionCaller.runSuspending(
  args: Array<Any?>,
  continuation: Continuation<Any?>,
): Any? {
  val block: suspend () -> Any? = { callSuspending(args) }
  return block.startCoroutineUninterceptedOrReturn(continuation)
}

internal class JSSuspendFunction0(
  private val caller: JSFunctionCaller,
) : Function1<Continuation<Any?>, Any?> {
  override fun invoke(c: Continuation<Any?>): Any? = caller.runSuspending(arrayOf(), c)
}

internal class JSSuspendFunction1(
  private val caller: JSFunctionCaller,
) : Function2<Any?, Continuation<Any?>, Any?> {
  override fun invoke(p1: Any?, c: Continuation<Any?>): Any? = caller.runSuspending(arrayOf(p1), c)
}

internal class JSSuspendFunction2(
  private val caller: JSFunctionCaller,
) : Function3<Any?, Any?, Continuation<Any?>, Any?> {
  override fun invoke(p1: Any?, p2: Any?, c: Continuation<Any?>): Any? =
    caller.runSuspending(arrayOf(p1, p2), c)
}

internal class JSSuspendFunction3(
  private val caller: JSFunctionCaller,
) : Function4<Any?, Any?, Any?, Continuation<Any?>, Any?> {
  override fun invoke(p1: Any?, p2: Any?, p3: Any?, c: Continuation<Any?>): Any? =
    caller.runSuspending(arrayOf(p1, p2, p3), c)
}

internal class JSSuspendFunction4(
  private val caller: JSFunctionCaller,
) : Function5<Any?, Any?, Any?, Any?, Continuation<Any?>, Any?> {
  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, c: Continuation<Any?>): Any? =
    caller.runSuspending(arrayOf(p1, p2, p3, p4), c)
}

internal class JSSuspendFunction5(
  private val caller: JSFunctionCaller,
) : Function6<Any?, Any?, Any?, Any?, Any?, Continuation<Any?>, Any?> {
  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, c: Continuation<Any?>,
  ): Any? = caller.runSuspending(arrayOf(p1, p2, p3, p4, p5), c)
}

internal class JSSuspendFunction6(
  private val caller: JSFunctionCaller,
) : Function7<Any?, Any?, Any?, Any?, Any?, Any?, Continuation<Any?>, Any?> {
  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, c: Continuation<Any?>,
  ): Any? = caller.runSuspending(arrayOf(p1, p2, p3, p4, p5, p6), c)
}

internal class JSSuspendFunction7(
  private val caller: JSFunctionCaller,
) : Function8<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Continuation<Any?>, Any?> {
  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, p7: Any?, c: Continuation<Any?>,
  ): Any? = caller.runSuspending(arrayOf(p1, p2, p3, p4, p5, p6, p7), c)
}

internal class JSSuspendFunction8(
  private val caller: JSFunctionCaller,
) : Function9<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?, Continuation<Any?>, Any?> {
  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, p7: Any?, p8: Any?,
    c: Continuation<Any?>,
  ): Any? = caller.runSuspending(arrayOf(p1, p2, p3, p4, p5, p6, p7, p8), c)
}
