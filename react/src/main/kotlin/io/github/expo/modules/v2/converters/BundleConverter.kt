package io.github.expo.modules.v2.converters

import android.os.Bundle
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter

/**
 * `android.os.Bundle`, which crosses as a JavaScript object. Its bridge is the one a
 * `Map<String, Any?>` uses - a map of dynamic values - so JavaScript cannot tell the two apart.
 *
 * See [BundleMapping] for what each value becomes in either direction.
 */
class BundleConverter(
  isNullable: Boolean
) : TypeConverter<Bundle>(isNullable) {
  /** The converter of every value in the bridge map: what `Map<String, Any?>` holds. */
  private val valueConverter = DynamicConverter(isNullable = true)

  override val kind: CppType get() = CppType.MAP

  override fun writeTypeParameters(out: TypeCodesWriter) {
    valueConverter.writeTypeCodes(out)
  }

  // The bridge is a Map, never the Bundle itself.
  override val isPassthrough: Boolean get() = false

  @Suppress("UNCHECKED_CAST")
  override fun fromJni(value: Any?): Bundle? =
    handleNullable(value) { BundleMapping.toBundle(it as Map<String, Any?>) }

  override fun toJni(value: Bundle?): Any? =
    value?.let(BundleMapping::toMap)

  // The same layout `MapConverter` writes for a `Map<String, Any?>`.
  override fun writeToBuffer(buf: BinaryBuffer, value: Bundle?) =
    buf.writePresent(value) { bundle ->
      val map = BundleMapping.toMap(bundle)
      putInt(map.size)
      for ((key, item) in map) {
        putString(key)
        valueConverter.writeToBuffer(this, item)
      }
    }

  override fun readFromBuffer(buf: BinaryBuffer): Bundle? =
    buf.readPresent {
      val count = getInt()
      val map = HashMap<String, Any?>(count, 1.0f)
      repeat(count) {
        map[getString()] = valueConverter.readFromBuffer(this)
      }
      BundleMapping.toBundle(map)
    }
}

/**
 * How a Bundle and the dynamic values of a JavaScript object map onto each other.
 *
 * JavaScript to Kotlin, following React Native's `Arguments.toBundle`:
 *
 * | JavaScript value       | Stored in the Bundle as                                         |
 * |------------------------|-----------------------------------------------------------------|
 * | `null` / `undefined`   | `putString(key, null)` - the key stays, its value is null       |
 * | boolean                | `putBoolean`                                                    |
 * | number                 | `putDouble` (an `Int`/`Long`/`Float` the buffer decoded keeps its type) |
 * | string                 | `putString`                                                     |
 * | object                 | `putBundle`, converted the same way                             |
 * | array                  | `putSerializable` of an `ArrayList`: elements converted the same way, objects as Bundles |
 * | `ArrayBuffer`          | `putByteArray`                                                  |
 *
 * Kotlin to JavaScript, whatever a Bundle holds:
 *
 * | Bundle value                                     | JavaScript value                     |
 * |--------------------------------------------------|--------------------------------------|
 * | null                                             | `null`                               |
 * | `Boolean`, `String`, `Int`, `Long`, `Float`, `Double` | itself                          |
 * | `Byte`, `Short`                                  | a number                             |
 * | `Char`, any other `CharSequence`                 | a string                             |
 * | `Bundle`, `Map`                                  | an object, converted the same way (map keys by `toString`) |
 * | `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, `BooleanArray` | an array of numbers or booleans |
 * | `ByteArray`                                      | what the bridge makes of a `ByteArray` |
 * | `ShortArray`, `CharArray`, `Array<*>`, any `Collection` | an array, converted the same way |
 * | an enum entry                                    | its name                             |
 * | `android.net.Uri`                                | its string form                      |
 *
 * Anything else - most `Parcelable`s and `Serializable`s - has no JavaScript form, and converting
 * it throws, naming the key, instead of dropping the value.
 */
internal object BundleMapping {
  fun toBundle(map: Map<String, Any?>): Bundle {
    val bundle = Bundle(map.size)
    for ((key, value) in map) {
      put(bundle, key, value)
    }
    return bundle
  }

  @Suppress("UNCHECKED_CAST")
  private fun put(bundle: Bundle, key: String, value: Any?) {
    when (value) {
      null -> bundle.putString(key, null)
      is Boolean -> bundle.putBoolean(key, value)
      is Double -> bundle.putDouble(key, value)
      is Int -> bundle.putInt(key, value)
      is Long -> bundle.putLong(key, value)
      is Float -> bundle.putFloat(key, value)
      is String -> bundle.putString(key, value)
      is Map<*, *> -> bundle.putBundle(key, toBundle(value as Map<String, Any?>))
      is List<*> -> bundle.putSerializable(key, toBundleList(value))
      is ByteArray -> bundle.putByteArray(key, value)
      else -> throw IllegalArgumentException(
        "Cannot store the ${value.javaClass.name} under \"$key\" in a Bundle"
      )
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun toBundleList(list: List<*>): ArrayList<Any?> =
    list.mapTo(ArrayList(list.size)) { item ->
      when (item) {
        is Map<*, *> -> toBundle(item as Map<String, Any?>)
        is List<*> -> toBundleList(item)
        else -> item
      }
    }

  fun toMap(bundle: Bundle): Map<String, Any?> {
    val keys = bundle.keySet()
    val map = HashMap<String, Any?>(keys.size, 1.0f)
    for (key in keys) {
      @Suppress("DEPRECATION")
      map[key] = toJsValue(bundle.get(key), key)
    }
    return map
  }

  private fun toJsValue(value: Any?, key: String): Any? =
    when (value) {
      null, is Boolean, is String, is Int, is Long, is Float, is Double -> value
      is Byte -> value.toInt()
      is Short -> value.toInt()
      is Char -> value.toString()
      is CharSequence -> value.toString()
      is Bundle -> toMap(value)
      is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to toJsValue(v, key) }
      // The arrays the bridge carries as they are.
      is IntArray, is LongArray, is FloatArray, is DoubleArray, is BooleanArray, is ByteArray -> value
      is ShortArray -> value.map { it.toInt() }
      is CharArray -> value.map { it.toString() }
      is Array<*> -> value.map { toJsValue(it, key) }
      is Collection<*> -> value.map { toJsValue(it, key) }
      is Enum<*> -> value.name
      is android.net.Uri -> value.toString()
      else -> throw IllegalArgumentException(
        "The Bundle value under \"$key\" is a ${value.javaClass.name}, which has no JavaScript form"
      )
    }
}
