package expo.modules.v2.async

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * A [CoroutineDispatcher] that runs on the JS thread, for a module body that has to get back there
 * deliberately: `withContext(runtime.asyncContext.dispatcher) { … }`.
 *
 * A `@JS suspend` export does NOT run on this dispatcher. It runs on [kotlinx.coroutines.Dispatchers.Unconfined],
 * so it starts inline on the calling JS thread and then follows whatever thread each suspension
 * resumes on. Settling is safe regardless, because [Promise] posts through the scheduler.
 */
class JSDispatcher(private val scheduler: JSScheduler) : CoroutineDispatcher() {
  override fun dispatch(context: CoroutineContext, block: Runnable) = scheduler.post(block)

  override fun toString(): String = "JSDispatcher"
}
