package expo.modules.v2.converters

import expo.modules.v2.types.CppType
import expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer
import java.net.URL

class UrlConverter(
  isNullable: Boolean
) : TypeConverter<URL>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.STRING.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): URL? =
    handleNullable(value) { URL(it as String) }

  override fun toJni(value: URL?): Any? =
    value?.toString()

  override fun writeToBuffer(buf: BinaryBuffer, value: URL?) =
    buf.writePresent(value) { putString(it.toString()) }

  override fun readFromBuffer(buf: BinaryBuffer): URL? =
    buf.readPresent { URL(getString()) }
}
