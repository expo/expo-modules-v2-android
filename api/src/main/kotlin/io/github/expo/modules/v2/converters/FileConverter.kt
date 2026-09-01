package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer
import java.io.File

class FileConverter(
  isNullable: Boolean
) : TypeConverter<File>(isNullable) {
  override val codes: TypeCodes
    get() = CppType.STRING.nullable(isNullable).toTypeCodes()

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): File? =
    handleNullable(value) { File(it as String) }

  override fun toJni(value: File?): Any? =
    value?.absolutePath

  override fun writeToBuffer(buf: BinaryBuffer, value: File?) =
    buf.writePresent(value) { putString(it.absolutePath) }

  override fun readFromBuffer(buf: BinaryBuffer): File? =
    buf.readPresent { File(getString()) }
}
