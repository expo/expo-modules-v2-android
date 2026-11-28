package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.sharedobjects.SharedClassId
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter

internal class SharedObjectConverter(
  private val classId: SharedClassId,
  private val sharedClass: Class<out SharedObject>,
  isNullable: Boolean,
) : TypeConverter<SharedObject>(isNullable) {
  override val kind: CppType get() = CppType.SHARED_OBJECT

  override fun writeTypeParameters(out: TypeCodesWriter) {
    out.add(classId.value)
  }

  override val isPassthrough: Boolean get() = true

  override fun fromJni(value: Any?): SharedObject? =
    handleNullable(value) { it as SharedObject }

  override fun toJni(value: SharedObject?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: SharedObject?) {
    error("No typed encoding for a shared object (${sharedClass.name})")
  }

  override fun readFromBuffer(buf: BinaryBuffer): SharedObject? {
    error("No typed decoding for a shared object (${sharedClass.name})")
  }
}
