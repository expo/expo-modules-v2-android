package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer
import java.net.URI

class UriConverter(
  isNullable: Boolean
) : TypeConverter<URI>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.STRING.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): URI? =
    handleNullable(value) { URI.create(it as String) }

  override fun toJni(value: URI?): Any? =
    value?.toString()

  override fun writeToBuffer(buf: BinaryBuffer, value: URI?) =
    buf.writePresent(value) { putString(it.toString())}

  override fun readFromBuffer(buf: BinaryBuffer): URI? =
    buf.readPresent { URI.create(getString()) }
}
