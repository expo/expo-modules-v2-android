package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import java.io.File

class FileConverter(
  isNullable: Boolean,
) : TypeConverter<File>(isNullable) {
  override val kind: CppType get() = CppType.STRING

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
