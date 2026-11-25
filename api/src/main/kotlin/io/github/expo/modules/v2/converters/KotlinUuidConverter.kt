package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `kotlin.uuid.Uuid`, which crosses as its string form: lowercase hex-and-dash on the way out, and
 * on the way in whatever [Uuid.parse] takes - hex-and-dash or 32 hex digits, in either case. A
 * string that is no UUID is refused, so a union variant of it does not take every string.
 */
@OptIn(ExperimentalUuidApi::class)
class KotlinUuidConverter(
  isNullable: Boolean
) : TypeConverter<Uuid>(isNullable) {
  override val kind: CppType get() = CppType.STRING

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Uuid? =
    handleNullable(value) { Uuid.parse(it as String) }

  override fun toJni(value: Uuid?): Any? =
    value?.toString()

  override fun writeToBuffer(buf: BinaryBuffer, value: Uuid?) =
    buf.writePresent(value) { putString(it.toString()) }

  override fun readFromBuffer(buf: BinaryBuffer): Uuid? =
    buf.readPresent { Uuid.parse(getString()) }
}
