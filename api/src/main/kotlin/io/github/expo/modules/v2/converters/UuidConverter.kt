package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.kolibri.binary.BinaryBuffer
import java.util.UUID

/**
 * `java.util.UUID`, which crosses as its canonical string form: `8-4-4-4-12` hex digits, in either
 * case on the way in and lower case on the way out. Any other string is refused, so a union variant
 * of it hands such a string on to the next variant.
 */
class UuidConverter(
  isNullable: Boolean
) : TypeConverter<UUID>(isNullable) {
  override val kind: CppType get() = CppType.STRING

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): UUID? =
    handleNullable(value) { parseCanonicalUuid(it as String) }

  override fun toJni(value: UUID?): Any? =
    value?.toString()

  override fun writeToBuffer(buf: BinaryBuffer, value: UUID?) =
    buf.writePresent(value) { putString(it.toString()) }

  override fun readFromBuffer(buf: BinaryBuffer): UUID? =
    buf.readPresent { parseCanonicalUuid(getString()) }
}

/**
 * [value] as a UUID, only when it is in the canonical form. [UUID.fromString] alone is lenient: it
 * takes `1-2-3-4-5` and silently truncates over-long groups, so a string it accepts can come back
 * as a different one.
 */
internal fun parseCanonicalUuid(value: String): UUID {
  require(isCanonicalUuid(value)) {
    "'$value' is not a UUID - expected 8-4-4-4-12 hexadecimal digits"
  }
  return UUID.fromString(value)
}

private fun isCanonicalUuid(value: String): Boolean {
  if (value.length != 36) {
    return false
  }
  for (i in value.indices) {
    val c = value[i]
    val ok = when (i) {
      8, 13, 18, 23 -> c == '-'
      else -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
    if (!ok) {
      return false
    }
  }
  return true
}
