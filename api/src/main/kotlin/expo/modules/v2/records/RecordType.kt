package expo.modules.v2.records

import expo.modules.v2.jni.jniDescriptor

internal data class RecordType<T : Record>(
  val schemaId: SchemaId,
  val codec: RecordCodec<T>,
) {
  val schema: RecordSchema = codec.schema

  @Suppress("UNCHECKED_CAST")
  val anyCodec: RecordCodec<Record> get() = codec as RecordCodec<Record>

  fun toData(): RecordSchemaData {
    return RecordSchemaData(
      name = schema.name,
      jniDescriptor = codec.recordClass.jniDescriptor,
      bufferSafe = schema.bufferSafe,
      fieldNames = schema.fields.map { it.name }.toTypedArray(),
      fieldTypes =  marshallCodes(),
      fieldOptional = BooleanArray(schema.fields.size) { schema.fields[it].isOptional },
    )
  }

  private fun marshallCodes(): IntArray {
    val codes = IntArray(fieldCodesSize())
    var at = 0
    val fields = schema.fields
    fields.forEach { field ->
      val fieldCodes = field.type.converter.codes
      codes[at++] = fieldCodes.size
      fieldCodes.forEach { codes[at++] = it }
    }
    return codes
  }

  private fun fieldCodesSize(): Int {
    val fields = schema.fields
    return fields.fold(0) { acc, field ->
      acc + 1 + field.type.converter.codes.size // +1 because we have to include code sizes
    }
  }
}
