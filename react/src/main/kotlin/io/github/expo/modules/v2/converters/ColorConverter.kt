package io.github.expo.modules.v2.converters

import android.graphics.Color
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.binary.BinaryValueCodec
import io.github.expo.modules.v2.colors.ColorParser
import io.github.expo.modules.v2.react.ColorCompat
import io.github.expo.modules.v2.types.CppType

/**
 * `android.graphics.Color`. JavaScript passes a color in several forms - a number, a CSS string,
 * an array of components (see [ColorParser]) - so it crosses as a dynamic value and is read here.
 * It crosses back as a `#rrggbbaa` string, which every React Native color prop takes and
 * [ColorParser] reads back to the same color.
 *
 * The [Color] it makes comes from [ColorCompat], so below API 26 read it through [ColorCompat].
 */
class ColorConverter(
  isNullable: Boolean
) : TypeConverter<Color>(isNullable) {
  override val kind: CppType get() = CppType.ANY

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Color? =
    handleNullable(value, ::colorOf)

  override fun toJni(value: Color?): Any? =
    value?.let(::cssHexOf)

  override fun writeToBuffer(buf: BinaryBuffer, value: Color?) =
    buf.writePresent(value) { BinaryValueCodec.writeDynamic(this, cssHexOf(it)) }

  override fun readFromBuffer(buf: BinaryBuffer): Color? =
    buf.readPresent {
      val value = BinaryValueCodec.readDynamic(this)
        ?: throw IllegalArgumentException("A non-nullable color arrived as null")
      colorOf(value)
    }

  private fun colorOf(value: Any): Color {
    // A number is already an ARGB int: kept whole rather than rounded through float components.
    if (value is Number) {
      return ColorCompat.valueOf(ColorParser.argbOf(value))
    }
    val rgba = ColorParser.parse(value)
    return ColorCompat.valueOf(rgba.red, rgba.green, rgba.blue, rgba.alpha)
  }

  private fun cssHexOf(color: Color): String = ColorParser.toCssHex(ColorCompat.toArgb(color))
}
