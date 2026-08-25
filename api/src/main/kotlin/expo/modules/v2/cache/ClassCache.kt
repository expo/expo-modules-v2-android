package expo.modules.v2.cache

import expo.modules.v2.converters.TypeConverter

class ClassCache {
  private val nullableCache = Cache<Class<*>, TypeConverter<*>>()
  private val cache = Cache<Class<*>, TypeConverter<*>>()

  fun put(
    clazz: Class<*>,
    isNullable: Boolean,
    value: TypeConverter<*>
  ) {
    cache(isNullable).put(clazz, value)
  }

  fun get(clazz: Class<*>, isNullable: Boolean): TypeConverter<*>? =
    cache(isNullable).get(clazz)

  fun getOrPut(
    clazz: Class<*>,
    isNullable: Boolean,
    defaultValue: () -> TypeConverter<*>
  ): TypeConverter<*> {
    return cache(isNullable).getOrPut(clazz, defaultValue)
  }

  private fun cache(isNullable: Boolean): Cache<Class<*>, TypeConverter<*>> =
    if (isNullable) {
      nullableCache
    } else {
      cache
    }

  class ClassCacheBuilder {
    private val entries = mutableListOf<Entry>()

    fun build(): ClassCache {
      val cache = ClassCache()
      for (entry in entries) {
        cache.put(entry.type, false, entry.converter)
        cache.put(entry.type, true, entry.nullableConverter)
      }

      return cache
    }

    fun entry(
      type: Class<*>,
      converter: TypeConverter<*>,
      nullableConverter: TypeConverter<*>,
    ): Entry = Entry(type, converter, nullableConverter)

    fun entry(
      type: Class<*>,
      converterFactory: (Boolean) -> TypeConverter<*>,
    ): Entry = Entry(type, converterFactory(false), converterFactory(true))

    operator fun Entry.unaryPlus() {
      entries += this
    }

    class Entry internal constructor(
      internal val type: Class<*>,
      internal val converter: TypeConverter<*>,
      internal val nullableConverter: TypeConverter<*>,
    )
  }
}

inline fun classCacheOf(builder: ClassCache.ClassCacheBuilder.() -> Unit): ClassCache {
  return ClassCache.ClassCacheBuilder().apply(builder).build()
}
