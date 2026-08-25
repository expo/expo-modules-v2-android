package expo.modules.v2.records

import expo.modules.v2.records.readers.MapRecordReader
import expo.modules.v2.records.writers.MapRecordWriter
import expo.modules.v2.records.readers.RecordReader
import expo.modules.v2.records.writers.RecordWriter

interface RecordCodec<T : Record> {
  val recordClass: Class<T>
  val schema: RecordSchema
  fun encode(value: T, writer: RecordWriter)
  fun decode(reader: RecordReader): T

  fun toMap(value: T): Map<String, Any?> {
    val writer = MapRecordWriter(RecordRegistry.typeFor(this))
    return writer.encode(value)
  }

  fun fromMap(map: Map<String, Any?>): T =
    decode(MapRecordReader(RecordRegistry.typeFor(this), map))
}
