package io.github.expo.modules.v2.testsupport

import io.github.expo.hermes.HermesRuntime as HermesEngine
import io.github.expo.modules.v2.ExpoContext
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.jsi.AttachedRuntime

/**
 * A Hermes-backed [AttachedRuntime] for desktop tests and benchmarks.
 *
 * The VM comes from hermes-tests-environment, which is the host here the way React Native is the
 * host in an app: it creates the `jsi::Runtime` and owns it. This class only pairs one such VM with
 * the modules installed into it, so a test can write `HermesRuntime().use { ... }` and get both
 * halves torn down in the right order.
 *
 * The thread that runs this constructor becomes the runtime's JS thread — a `jsi::Runtime` is
 * thread-affine, and the default [AsyncContext] captures that thread as the one every promise
 * settles on.
 *
 * Pass one [ExpoContext] to several runtimes to let them share its modules and shared object
 * instances; each runtime otherwise gets a context of its own.
 */
class HermesRuntime private constructor(
  private val engine: HermesEngine,
  asyncContext: AsyncContext,
  context: ExpoContext?,
) : AttachedRuntime(
  engine.pointer,
  asyncContext,
  ENGINE_NAME,
  context = context,
) {

  constructor(
    asyncContext: AsyncContext = AsyncContext(),
    context: ExpoContext? = null,
  ) : this(newEngine(), asyncContext, context)

  /**
   * Tears down the modules first and the VM second: the native runtime object drops pending
   * `jsi::Function`s while it is destroyed, which needs the VM still alive.
   */
  override fun close() {
    super.close()
    engine.close()
  }

  companion object {
    const val ENGINE_NAME: String = "hermes"

    private fun newEngine(): HermesEngine {
      ExpoHermes.ensureLoaded()
      return HermesEngine()
    }
  }
}
