package expo.modules.v2.converters

import expo.modules.v2.types.CppType
import expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer
import java.nio.file.Path
import java.nio.file.Paths

class PathConverter(
  isNullable: Boolean
) : TypeConverter<Path>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.STRING.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Path? =
    handleNullable(value) { Paths.get(it as String) }

  override fun toJni(value: Path?): Any? =
    value?.toString()

  override fun writeToBuffer(buf: BinaryBuffer, value: Path?) =
    buf.writePresent(value) { putString(it.toString()) }

  override fun readFromBuffer(buf: BinaryBuffer): Path? =
    buf.readPresent { Paths.get(getString()) }
}
