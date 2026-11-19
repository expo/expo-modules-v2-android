package io.github.expo.modules.v2.unions

import io.github.expo.modules.v2.cache.Cache
import io.github.expo.modules.v2.loader.ExpoClassLoader

/**
 * Finds the [UnionCodec] of a union class. Native code never asks: a union's type codes spell out
 * its variants wherever it is used.
 */
object UnionRegistry {
  private val byClass = Cache<Class<*>, UnionType<*>>()

  fun register(codec: UnionCodec<*>) {
    requireNotNull(codec.unionClass) {
      "${codec.javaClass.name} ran `UnionRegistry.register(this)` before `unionClass` was " +
        "initialized - declare `override val unionClass` above the `init` block"
    }
    registerCodec(codec)
  }

  /**
   * The registered [UnionType] for [clazz], or `null` when the class is not a union.
   *
   * Like a record's, a union's codec registers itself while the union type initializes, so a miss
   * forces that once and looks again. The [Union] guard keeps every other class off that path.
   */
  internal fun typeFor(clazz: Class<*>): UnionType<*>? =
    byClass.get(clazz) ?: forceInitAndRetry(clazz)

  private fun forceInitAndRetry(clazz: Class<*>): UnionType<*>? {
    if (!Union::class.java.isAssignableFrom(clazz)) {
      return null
    }

    ExpoClassLoader.loadAndInitializeClass(clazz)

    return byClass.get(clazz)
  }

  @Synchronized
  private fun <T : Any> registerCodec(codec: UnionCodec<T>): UnionType<T> {
    byClass.get(codec.unionClass)?.let { existing ->
      require(existing.codec === codec) {
        "Class ${codec.unionClass.name} is already registered with a different UnionCodec"
      }
      error("Codec has been registered before")
    }

    return UnionType(codec).also { type ->
      byClass.put(codec.unionClass, type)
    }
  }
}
