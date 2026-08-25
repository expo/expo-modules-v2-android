package expo.modules.v2.converters

import expo.modules.v2.types.CppType
import expo.modules.v2.types.TypeCodes
import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer

class MapConverter(
  typeDescriptor: TypeDescriptor.Parametrized,
) : TypeConverter<Map<*, *>>(typeDescriptor.isNullable) {
  private val valueConverter: TypeConverter<Any> =
    typeDescriptor.params[1].anyConverter

  override val codes: TypeCodes =
    CppType.MAP.nullable(isNullable) + valueConverter.codes

  override val isPassthrough: Boolean get() = valueConverter.isPassthrough

  @Suppress("UNCHECKED_CAST")
  override fun fromJni(value: Any?): Map<*, *>? =
    handleNullable(value) { bridgeValue ->
      bridgeValue as Map<String, Any?>

      if (valueConverter.isPassthrough) {
        return@handleNullable bridgeValue
      }

      bridgeValue.mapValues { (_, item) -> valueConverter.fromJni(item) }
    }

  override fun toJni(value: Map<*, *>?): Any? {
    if (valueConverter.isPassthrough) {
      return value
    }

    return value?.mapValues { (_, item) ->
      valueConverter.toJni(item)
    }
  }

  override fun writeToBuffer(buf: BinaryBuffer, value: Map<*, *>?) =
    buf.writePresent(value) { map ->
      buf.putInt(map.size)
      for ((key, item) in map) {
        require(key is String) { "Cannot binary-encode a Map with non-String key" }
        buf.putString(key)
        valueConverter.writeToBuffer(buf, item)
      }
    }

  override fun readFromBuffer(buf: BinaryBuffer): Map<*, *>? =
    buf.readPresent {
      val count = buf.getInt()
      // The decoded map is fully populated here and exposed as a read-only Map. A load factor of
      // one lets the known entry count fit without allocating an oversized table or resizing.
      val map = HashMap<String, Any?>(count, 1.0f)
      repeat(count) {
        map[buf.getString()] = valueConverter.readFromBuffer(buf)
      }
      map
    }
}
