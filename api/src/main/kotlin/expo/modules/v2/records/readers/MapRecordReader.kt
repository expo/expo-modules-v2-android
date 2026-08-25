package expo.modules.v2.records.readers

import expo.modules.v2.records.Record
import expo.modules.v2.records.RecordField
import expo.modules.v2.records.RecordType
import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter

internal class MapRecordReader(
  private val type: RecordType<*>,
  private val map: Map<String, Any?>,
) : RecordReader {
  private val fields = type.schema.fields

  private var currentIndex = 0

  private val nextField: RecordField get() = fields[currentIndex++]

  private val nextFieldName get() = nextField.name

  override fun readIsPresent(): Boolean {
    if (map.containsKey(fields[currentIndex].name)) {
      return true
    }
    currentIndex++
    return false
  }

  override fun readBoolean(): Boolean = map[nextFieldName] as Boolean

  override fun readBooleanOrNull(): Boolean? = map[nextFieldName] as Boolean?

  override fun readInt(): Int = map[nextFieldName] as Int

  override fun readIntOrNull(): Int? = map[nextFieldName] as Int?

  override fun readLong(): Long = map[nextFieldName] as Long

  override fun readLongOrNull(): Long? = map[nextFieldName] as Long?

  override fun readFloat(): Float = map[nextFieldName] as Float

  override fun readFloatOrNull(): Float? = map[nextFieldName] as Float?

  override fun readDouble(): Double = map[nextFieldName] as Double

  override fun readDoubleOrNull(): Double? = map[nextFieldName] as Double?

  @Suppress("UNCHECKED_CAST")
  override fun <T> read(schema: TypeDescriptor): T {
    val (name, type) = nextField
    val converter = type.anyConverter
    val value = map[name]

    // The field's converter answers for a null the map carries, or leaves out entirely; only a
    // present value crosses untouched.
    return if (value != null && converter.isPassthrough) {
      value as T
    } else {
      converter.fromJni(value) as T
    }
  }

  fun decode(): Record = type.anyCodec.decode(this)
}
