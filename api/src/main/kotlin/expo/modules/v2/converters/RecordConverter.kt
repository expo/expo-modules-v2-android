package expo.modules.v2.converters

import expo.modules.v2.records.Record
import expo.modules.v2.records.RecordType
import expo.modules.v2.records.readers.BufferRecordReader
import expo.modules.v2.records.readers.MapRecordReader
import expo.modules.v2.records.writers.BufferRecordWriter
import expo.modules.v2.records.writers.MapRecordWriter
import expo.modules.v2.types.CppType
import expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer

internal class RecordConverter(
  private val recordType: RecordType<*>,
  isNullable: Boolean,
) : TypeConverter<Record>(isNullable) {
  override val codes: TypeCodes =
    CppType.RECORD.nullable(isNullable) + recordType.schemaId

  override val isPassthrough: Boolean get() = false

  @Suppress("UNCHECKED_CAST")
  override fun fromJni(value: Any?): Record? =
    handleNullable(value) {
      MapRecordReader(recordType, it as Map<String, Any?>).decode()
    }

  override fun toJni(value: Record?): Any? =
    value?.let {
      val writer = MapRecordWriter(recordType)
      writer.encode(it)
    }

  override fun writeToBuffer(buf: BinaryBuffer, value: Record?) =
    buf.writePresent(value) { record ->
      recordType.anyCodec.encode(record, BufferRecordWriter(buf))
    }

  override fun readFromBuffer(buf: BinaryBuffer): Record? =
    buf.readPresent {
      recordType.anyCodec.decode(BufferRecordReader(buf))
    }
}
