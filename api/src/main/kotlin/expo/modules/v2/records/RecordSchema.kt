package expo.modules.v2.records

import expo.modules.v2.types.TypeDescriptor

class RecordSchema(
  val name: String,
  val bufferSafe: Boolean,
  vararg fields: RecordField,
) {
  val fields: List<RecordField> = fields.toList()
}

data class RecordField(
  val name: String,
  val type: TypeDescriptor,
  val isOptional: Boolean = false,
)
