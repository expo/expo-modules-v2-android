package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes
import io.github.expo.kolibri.binary.BinaryBuffer
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class DurationConverter(isNullable: Boolean) : TypeConverter<Duration>(isNullable) {
  override val codes: TypeCodes
    get() = if (isNullable) {
      CppType.BOX_DOUBLE.nullable(true).toTypeCodes()
    } else {
      CppType.DOUBLE.toTypeCodes()
    }

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Duration? =
    handleNullable(value) { (it as Double).toDuration(DurationUnit.SECONDS) }

  override fun toJni(value: Duration?): Any? =
    value?.toDouble(DurationUnit.SECONDS)

  override fun writeToBuffer(buf: BinaryBuffer, value: Duration?) =
    buf.writePresent(value) {
      putDouble(it.toDouble(DurationUnit.SECONDS))
    }

  override fun readFromBuffer(buf: BinaryBuffer): Duration? =
    buf.readPresent {
      getDouble().toDuration(DurationUnit.SECONDS)
    }
}
