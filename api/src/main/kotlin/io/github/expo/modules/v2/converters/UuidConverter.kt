package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.kolibri.binary.BinaryBuffer
import java.util.UUID

/**
 * `java.util.UUID`, which crosses as its canonical string form. [UUID.fromString] refuses a string
 * that is not a UUID, so a union variant of it hands such a string on to the next variant.
 */
class UuidConverter(
  isNullable: Boolean
) : TypeConverter<UUID>(isNullable) {
  override val kind: CppType get() = CppType.STRING

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): UUID? =
    handleNullable(value) { UUID.fromString(it as String) }

  override fun toJni(value: UUID?): Any? =
    value?.toString()

  override fun writeToBuffer(buf: BinaryBuffer, value: UUID?) =
    buf.writePresent(value) { putString(it.toString()) }

  override fun readFromBuffer(buf: BinaryBuffer): UUID? =
    buf.readPresent { UUID.fromString(getString()) }
}
