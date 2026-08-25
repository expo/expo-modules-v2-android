package expo.modules.v2.records.writers

import expo.modules.v2.records.Record
import expo.modules.v2.records.RecordField
import expo.modules.v2.records.RecordType
import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter

internal class MapRecordWriter(
  private val type: RecordType<*>,
) : RecordWriter {
  private val fields = type.schema.fields

  private var currentIndex = 0

  private val out = LinkedHashMap<String, Any?>(fields.size)

  private val nextField: RecordField get() = fields[currentIndex++]

  private val nextFieldName get() = nextField.name


  override fun write(value: Boolean) {
    out[nextFieldName] = value
  }

  override fun write(value: Boolean?) {
    out[nextFieldName] = value
  }

  override fun write(value: Int) {
    out[nextFieldName] = value
  }

  override fun write(value: Int?) {
    out[nextFieldName] = value
  }

  override fun write(value: Long) {
    out[nextFieldName] = value
  }

  override fun write(value: Long?) {
    out[nextFieldName] = value
  }

  override fun write(value: Float) {
    out[nextFieldName] = value
  }

  override fun write(value: Float?) {
    out[nextFieldName] = value
  }

  override fun write(value: Double) {
    out[nextFieldName] = value
  }

  override fun write(value: Double?) {
    out[nextFieldName] = value
  }

  override fun write(value: Any?, schema: TypeDescriptor) {
    val (name, type) = nextField
    val converter = type.anyConverter

    out[name] = if (converter.isPassthrough) {
      value
    } else {
      converter.toJni(value)
    }
  }

  fun encode(record: Record): Map<String, Any?> {
    type.anyCodec.encode(record, this)
    return out
  }
}
