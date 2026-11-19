package io.github.expo.modules.v2.records

import io.github.expo.modules.v2.jni.jniDescriptor
import io.github.expo.modules.v2.types.TypeCodesWriter

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

  /** Each field's type codes, length-prefixed, written through one writer. */
  private fun marshallCodes(): IntArray {
    val codes = TypeCodesWriter()
    schema.fields.forEach { field ->
      val count = codes.size
      codes.add(0) // the field's code count, set below
      field.type.converter.writeTypeCodes(codes)
      codes.set(count, codes.size - count - 1)
    }
    return codes.toIntArray()
  }
}
