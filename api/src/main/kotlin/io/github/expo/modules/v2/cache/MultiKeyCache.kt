package io.github.expo.modules.v2.cache

class MultiKeyCache<Key1 : Any, Key2 : Any, Value : Any> {
  private val byKey1 = Cache<Key1, Value>()
  private val byKey2 = Cache<Key2, Value>()

  fun put(key1: Key1, key2: Key2, value: Value) {
    byKey1.put(key1, value)
    byKey2.put(key2, value)
  }

  @JvmName("getKey1")
  fun get(key1: Key1): Value? = byKey1.get(key1)

  @JvmName("getKey2")
  fun get(key2: Key2): Value? = byKey2.get(key2)
}
