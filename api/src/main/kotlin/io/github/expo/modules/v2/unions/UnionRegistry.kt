package io.github.expo.modules.v2.unions

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.modules.v2.cache.Cache
import io.github.expo.modules.v2.loader.ExpoClassLoader

@CalledFromNative(by = "expo-modules-v2/jni/JUnionRegistry.h")
object UnionRegistry {
  private val byClass = Cache<Class<*>, UnionType<*>>()
  private val byId = Cache<UnionId, UnionType<*>>()

  @Volatile
  private var _nextUnionId = UnionId(1)

  private val nextUnionId: UnionId
    get() {
      return _nextUnionId++
    }

  fun register(codec: UnionCodec<*>) {
    requireNotNull(codec.unionClass) {
      "${codec.javaClass.name} ran `UnionRegistry.register(this)` before `unionClass` was " +
        "initialized — declare `override val unionClass` above the `init` block"
    }
    registerCodec(codec)
  }

  internal fun typeFor(unionId: UnionId): UnionType<*> =
    byId.get(unionId) ?: throw IllegalStateException(
      "Unknown union id $unionId - register it via UnionRegistry.register first",
    )

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

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JUnionRegistry.h")
  fun fetchSchema(unionId: Int): UnionSchemaData {
    val type = typeFor(UnionId(unionId))
    return type.toData()
  }

  @Synchronized
  private fun <T : Any> registerCodec(codec: UnionCodec<T>): UnionType<T> {
    byClass.get(codec.unionClass)?.let { existing ->
      require(existing.codec === codec) {
        "Class ${codec.unionClass.name} is already registered with a different UnionCodec"
      }
      error("Codec has been registered before")
    }

    val unionId = nextUnionId
    return UnionType(unionId, codec).also { type ->
      byClass.put(codec.unionClass, type)
      byId.put(unionId, type)
    }
  }
}
