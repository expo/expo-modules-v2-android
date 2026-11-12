package io.github.expo.modules.v2.jsi

import io.github.expo.modules.v2.JavaScriptException
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.converters.TypeConverter
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Everything a Kotlin lambda needs to stand for one JavaScript function: how to cross the values
 * and which thread it may run on.
 *
 * A JavaScript function can only be called from the JS thread. A plain lambda checks that and
 * throws anywhere else. A `suspend` lambda hops there on its own, calls the function, and waits
 * for the promise it may return.
 */
internal class JSFunctionCaller(
  private val handle: JavaScriptFunction,
  private val params: Array<TypeConverter<Any>>,
  private val returns: TypeConverter<Any>,
  private val returnsUnit: Boolean,
  private val asyncContext: AsyncContext,
) {
  fun call(args: Array<Any?>): Any? {
    check(asyncContext.scheduler.isOnJSThread) {
      "A JavaScript function can only be called on the JavaScript thread, but this is " +
        "${Thread.currentThread().name}. Declare the parameter as `suspend` to call it from anywhere."
    }

    val outcome = Outcome()
    handle.call(toBridge(args), outcome, awaitPromise = false)
    return outcome.take()
  }

  suspend fun callSuspending(args: Array<Any?>): Any? =
    if (asyncContext.scheduler.isOnJSThread) {
      await(args)
    } else {
      withContext(asyncContext.dispatcher) { await(args) }
    }

  private suspend fun await(args: Array<Any?>): Any? {
    val bridged = toBridge(args)
    return suspendCancellableCoroutine { continuation ->
      handle.call(bridged, ResumingCallback(continuation), awaitPromise = true)
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

  private inner class Outcome : JavaScriptFunctionCallback() {
    private var value: Any? = null
    private var failure: Throwable? = null

    override fun resolve(value: Any?) {
      this.value = value
    }

    override fun reject(code: String, message: String, stack: String?) {
      failure = JavaScriptException(message, code, stack)
    }

    fun take(): Any? {
      failure?.let { throw it }
      return fromBridge(value)
    }
  }

  private inner class ResumingCallback(
    private val continuation: Continuation<Any?>,
  ) : JavaScriptFunctionCallback() {
    override fun resolve(value: Any?) {
      val converted = try {
        fromBridge(value)
      } catch (throwable: Throwable) {
        continuation.resumeWithException(throwable)
        return
      }
      continuation.resume(converted)
    }

    override fun reject(code: String, message: String, stack: String?) {
      continuation.resumeWithException(JavaScriptException(message, code, stack))
    }
  }
}

/**
 * A `(P1, …, Pn) -> R` lambda.
 *
 * One class serves every arity because the generated code only ever casts it to the lambda's
 * type. `Function0` through `Function8` are all implemented, and [arity] is what Kotlin's own
 * function-type cast consults to decide which one the object is.
 */
@Suppress("TooManyFunctions")
internal class JSFunction(
  private val caller: JSFunctionCaller,
  override val arity: Int,
) : kotlin.jvm.internal.FunctionBase<Any?>,
  Function0<Any?>, Function1<Any?, Any?>, Function2<Any?, Any?, Any?>, Function3<Any?, Any?, Any?, Any?>,
  Function4<Any?, Any?, Any?, Any?, Any?>, Function5<Any?, Any?, Any?, Any?, Any?, Any?>,
  Function6<Any?, Any?, Any?, Any?, Any?, Any?, Any?>,
  Function7<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?>,
  Function8<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?> {

  override fun invoke(): Any? = caller.call(arrayOf<Any?>())

  override fun invoke(p1: Any?): Any? = caller.call(arrayOf(p1))

  override fun invoke(p1: Any?, p2: Any?): Any? = caller.call(arrayOf(p1, p2))

  override fun invoke(p1: Any?, p2: Any?, p3: Any?): Any? = caller.call(arrayOf(p1, p2, p3))

  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?): Any? =
    caller.call(arrayOf(p1, p2, p3, p4))

  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?): Any? =
    caller.call(arrayOf(p1, p2, p3, p4, p5))

  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?): Any? =
    caller.call(arrayOf(p1, p2, p3, p4, p5, p6))

  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, p7: Any?): Any? =
    caller.call(arrayOf(p1, p2, p3, p4, p5, p6, p7))

  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, p7: Any?, p8: Any?,
  ): Any? = caller.call(arrayOf(p1, p2, p3, p4, p5, p6, p7, p8))
}

/**
 * A `suspend (P1, …, Pn) -> R` lambda.
 *
 * The JVM sees it as a function of n + 1 parameters, the last of which is the continuation, so it
 * implements `Function1` through `Function9` and reports an [arity] of n + 1. Each `invoke` is the
 * usual suspend protocol: it returns the value, or the marker that it suspended.
 */
@Suppress("TooManyFunctions", "UNCHECKED_CAST")
internal class JSSuspendFunction(
  private val caller: JSFunctionCaller,
  override val arity: Int,
) : kotlin.jvm.internal.FunctionBase<Any?>,
  Function1<Continuation<Any?>, Any?>,
  Function2<Any?, Continuation<Any?>, Any?>,
  Function3<Any?, Any?, Continuation<Any?>, Any?>,
  Function4<Any?, Any?, Any?, Continuation<Any?>, Any?>,
  Function5<Any?, Any?, Any?, Any?, Continuation<Any?>, Any?>,
  Function6<Any?, Any?, Any?, Any?, Any?, Continuation<Any?>, Any?>,
  Function7<Any?, Any?, Any?, Any?, Any?, Any?, Continuation<Any?>, Any?>,
  Function8<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Continuation<Any?>, Any?>,
  Function9<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?, Continuation<Any?>, Any?> {

  private fun run(args: Array<Any?>, continuation: Continuation<Any?>): Any? {
    val block: suspend () -> Any? = { caller.callSuspending(args) }
    return block.startCoroutineUninterceptedOrReturn(continuation)
  }

  override fun invoke(c: Continuation<Any?>): Any? = run(arrayOf<Any?>(), c)

  override fun invoke(p1: Any?, c: Continuation<Any?>): Any? = run(arrayOf(p1), c)

  override fun invoke(p1: Any?, p2: Any?, c: Continuation<Any?>): Any? = run(arrayOf(p1, p2), c)

  override fun invoke(p1: Any?, p2: Any?, p3: Any?, c: Continuation<Any?>): Any? =
    run(arrayOf(p1, p2, p3), c)

  override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, c: Continuation<Any?>): Any? =
    run(arrayOf(p1, p2, p3, p4), c)

  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, c: Continuation<Any?>,
  ): Any? = run(arrayOf(p1, p2, p3, p4, p5), c)

  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, c: Continuation<Any?>,
  ): Any? = run(arrayOf(p1, p2, p3, p4, p5, p6), c)

  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, p7: Any?, c: Continuation<Any?>,
  ): Any? = run(arrayOf(p1, p2, p3, p4, p5, p6, p7), c)

  override fun invoke(
    p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, p6: Any?, p7: Any?, p8: Any?,
    c: Continuation<Any?>,
  ): Any? = run(arrayOf(p1, p2, p3, p4, p5, p6, p7, p8), c)
}
