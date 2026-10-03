package io.github.expo.modules.v2

import io.github.expo.modules.v2.logging.platformLogger
import io.github.expo.modules.v2.modules.ModuleRegistry

open class ExpoContext : AutoCloseable {
  val moduleRegistry: ModuleRegistry = ModuleRegistry(this)

  /** Set by [close]. An object bound to a closed context is free to be bound to another one. */
  @Volatile
  var isClosed: Boolean = false
    private set

  /** Set when [close] starts, so a second call does nothing. Guarded by `this`. */
  private var isClosing = false

  fun <T : Module> module(type: Class<T>): T? =
    if (isClosed) {
      null
    } else {
      moduleRegistry.module(type)
    }

  override fun close() {
    synchronized(this) {
      if (isClosing) {
        return
      }
      isClosing = true
    }

    for (module in moduleRegistry.modules()) {
      try {
        module.onDestroy()
      } catch (throwable: Throwable) {
        platformLogger.error("onDestroy of ${module.javaClass.name} failed", throwable)
      }
    }
    isClosed = true
  }
}

inline fun <reified T : Module> ExpoContext.module(): T? = module(T::class.java)
