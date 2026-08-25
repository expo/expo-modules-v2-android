package expo.modules.v2.records

@JvmInline
internal value class SchemaId(val value: Int) {
  override fun toString(): String = "SchemaId($value)"

  operator fun inc(): SchemaId =
    SchemaId(value.inc())

}
