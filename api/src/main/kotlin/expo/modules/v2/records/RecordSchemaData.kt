package expo.modules.v2.records

/**
 * Used to move [RecordSchema] from Kotlin to C++
 */
class RecordSchemaData internal constructor(
  @JvmField val name: String,
  @JvmField val jniDescriptor: String,
  @JvmField val bufferSafe: Boolean,
  @JvmField val fieldNames: Array<String>,
  @JvmField val fieldTypes: IntArray,
  @JvmField val fieldOptional: BooleanArray,
)
