package expo.modules.v2.cache

import java.util.concurrent.ConcurrentHashMap

class Cache<Key : Any, Value : Any> {
  private val store: ConcurrentHashMap<Key, Value> = ConcurrentHashMap()

  fun put(key: Key, value: Value) {
    store[key] = value
  }

  fun get(key: Key): Value? = store[key]

  fun getOrPut(key: Key, defaultValue: () -> Value): Value {
    get(key)?.let { return it }

    val newValue = defaultValue()
    store[key] = newValue
    return newValue
  }
}
