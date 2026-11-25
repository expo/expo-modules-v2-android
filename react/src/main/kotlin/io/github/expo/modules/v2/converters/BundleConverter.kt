package io.github.expo.modules.v2.converters

import android.os.Bundle
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import java.util.Collections
import java.util.IdentityHashMap

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
 * | number                 | `putDouble` - always, see below                                 |
 * | string                 | `putString`                                                     |
 * | object                 | `putBundle`, converted the same way                             |
 * | array                  | `putSerializable` of an `ArrayList`: elements converted the same way, objects as Bundles |
 * | `ArrayBuffer`          | `putByteArray`                                                  |
 *
 * A JavaScript number always arrives as a `Double`: native code encodes every dynamic number as
 * one, on the JNI slot and on the buffer alike. Read it with `getDouble`. `getInt` and `getLong`
 * find no Int or Long under the key and return their default - so a Bundle that crossed to
 * JavaScript and back holds Doubles where it held Ints. The `Int`, `Long` and `Float` branches
 * below only guard a map built in Kotlin; nothing native produces them.
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
 * Converting throws an `IllegalArgumentException`, naming where the value sits - `extras.frame`,
 * `items[2]` - instead of dropping or guessing, when:
 *
 * - a value has no JavaScript form: most `Parcelable`s and `Serializable`s
 * - two keys of a `Map` read the same as strings, such as `1` and `"1"`
 * - a Bundle, map, list or array contains itself. One held in two places that do not contain each
 *   other is converted twice and is fine.
 */
internal object BundleMapping {
  fun toBundle(map: Map<String, Any?>): Bundle =
    toBundle(map, path = null, converting = identitySet())

  private fun toBundle(map: Map<*, *>, path: String?, converting: MutableSet<Any>): Bundle =
    nested(map, path, converting) {
      val bundle = Bundle(map.size)
      for ((key, value) in map) {
        require(key is String) { "A key of the object at ${describe(path)} is not a string: $key" }
        put(bundle, key, value, keyPath(path, key), converting)
      }
      bundle
    }

  private fun put(bundle: Bundle, key: String, value: Any?, path: String, converting: MutableSet<Any>) {
    when (value) {
      null -> bundle.putString(key, null)
      is Boolean -> bundle.putBoolean(key, value)
      is Double -> bundle.putDouble(key, value)
      // Defensive: native code hands every number over as a Double.
      is Int -> bundle.putInt(key, value)
      is Long -> bundle.putLong(key, value)
      is Float -> bundle.putFloat(key, value)
      is String -> bundle.putString(key, value)
      is Map<*, *> -> bundle.putBundle(key, toBundle(value, path, converting))
      is List<*> -> bundle.putSerializable(key, toBundleList(value, path, converting))
      is ByteArray -> bundle.putByteArray(key, value)
      else -> throw IllegalArgumentException(
        "Cannot store the ${value.javaClass.name} at $path in a Bundle"
      )
    }
  }

  private fun toBundleList(list: List<*>, path: String, converting: MutableSet<Any>): ArrayList<Any?> =
    nested(list, path, converting) {
      list.mapIndexedTo(ArrayList(list.size)) { index, item ->
        val itemPath = "$path[$index]"
        when (item) {
          is Map<*, *> -> toBundle(item, itemPath, converting)
          is List<*> -> toBundleList(item, itemPath, converting)
          else -> item
        }
      }
    }

  fun toMap(bundle: Bundle): Map<String, Any?> =
    toMap(bundle, path = null, converting = identitySet())

  private fun toMap(bundle: Bundle, path: String?, converting: MutableSet<Any>): Map<String, Any?> =
    nested(bundle, path, converting) {
      val keys = bundle.keySet()
      val map = HashMap<String, Any?>(keys.size, 1.0f)
      for (key in keys) {
        @Suppress("DEPRECATION")
        map[key] = toJsValue(bundle.get(key), keyPath(path, key), converting)
      }
      map
    }

  private fun toJsValue(value: Any?, path: String, converting: MutableSet<Any>): Any? =
    when (value) {
      null, is Boolean, is String, is Int, is Long, is Float, is Double -> value
      is Byte -> value.toInt()
      is Short -> value.toInt()
      is Char -> value.toString()
      is CharSequence -> value.toString()
      is Bundle -> toMap(value, path, converting)
      is Map<*, *> -> mapToJs(value, path, converting)
      // The arrays the bridge carries as they are.
      is IntArray, is LongArray, is FloatArray, is DoubleArray, is BooleanArray, is ByteArray -> value
      is ShortArray -> value.map { it.toInt() }
      is CharArray -> value.map { it.toString() }
      is Array<*> -> nested(value, path, converting) {
        value.mapIndexed { index, item -> toJsValue(item, "$path[$index]", converting) }
      }
      is Collection<*> -> nested(value, path, converting) {
        value.mapIndexed { index, item -> toJsValue(item, "$path[$index]", converting) }
      }
      is Enum<*> -> value.name
      is android.net.Uri -> value.toString()
      else -> throw IllegalArgumentException(
        "The Bundle value at $path is a ${value.javaClass.name}, which has no JavaScript form"
      )
    }

  private fun mapToJs(map: Map<*, *>, path: String, converting: MutableSet<Any>): Map<String, Any?> =
    nested(map, path, converting) {
      val result = HashMap<String, Any?>(map.size, 1.0f)
      for ((key, item) in map) {
        val name = key.toString()
        require(!result.containsKey(name)) {
          "Two keys of the map at $path both read \"$name\" as a string, so one would overwrite the other"
        }
        result[name] = toJsValue(item, keyPath(path, name), converting)
      }
      result
    }

  /**
   * Converts the container [value] with [convert], refusing one that is already being converted
   * further up: it contains itself, and converting it would never end.
   */
  private inline fun <T> nested(
    value: Any,
    path: String?,
    converting: MutableSet<Any>,
    convert: () -> T,
  ): T {
    require(converting.add(value)) {
      "The ${value.javaClass.simpleName} at ${describe(path)} contains itself"
    }
    try {
      return convert()
    } finally {
      converting.remove(value)
    }
  }

  private fun identitySet(): MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

  private fun keyPath(path: String?, key: String): String =
    if (path == null) key else "$path.$key"

  private fun describe(path: String?): String = path ?: "the top level"
}
