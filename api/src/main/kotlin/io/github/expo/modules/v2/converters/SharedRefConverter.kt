package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.sharedobjects.SharedClassId
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import io.github.expo.kolibri.binary.BinaryBuffer

internal class SharedRefConverter(
  private val classId: SharedClassId,
  private val refClass: Class<*>,
  isNullable: Boolean,
) : TypeConverter<SharedRef<*>>(isNullable) {
  override val kind: CppType get() = CppType.SHARED_OBJECT

  override fun writeTypeParameters(out: TypeCodesWriter) {
    out.add(classId.value)
  }

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): SharedRef<*>? =
    handleNullable(value) { bridgeValue ->
      val ref = bridgeValue as SharedRef<*>
      if (!refClass.isInstance(ref.ref)) {
        throw IllegalArgumentException(
          "Expected a SharedRef<${refClass.simpleName}>, got ${ref.javaClass.simpleName} " +
            "carrying a ${ref.ref.javaClass.simpleName}",
        )
      }
      ref
    }

  override fun toJni(value: SharedRef<*>?): Any? = value

  override fun writeToBuffer(buf: BinaryBuffer, value: SharedRef<*>?) {
    error("No typed encoding for a shared ref (SharedRef<${refClass.name}>)")
  }

  override fun readFromBuffer(buf: BinaryBuffer): SharedRef<*>? {
    error("No typed decoding for a shared ref (SharedRef<${refClass.name}>)")
  }
}
