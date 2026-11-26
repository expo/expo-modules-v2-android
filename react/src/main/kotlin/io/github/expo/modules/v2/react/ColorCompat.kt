package io.github.expo.modules.v2.react

import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi

/**
 * The [Color] instance API on every API level this library supports.
 *
 * [Color]'s instances - [Color.valueOf] and the component getters - arrived in API 26, while this
 * library runs from API 24. A `Color` argument therefore reaches a module as a [Color] on every
 * level, but below API 26 it is one this class made: read it through [toArgb], [red], [green],
 * [blue] and [alpha] here, not through the `Color` methods, which do not exist there. From API 26
 * these forward to those methods.
 *
 * The same API as Expo Modules v1's `expo.modules.kotlin.types.ColorCompat`, in this package. Below
 * API 26 each one reads only the colors it made itself, so a module that moves over has to use
 * this one for every color it hands here.
 */
class ColorCompat private constructor() {
  companion object {
    @JvmStatic
    fun valueOf(@ColorInt color: Int): Color {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return Api26Impl.valueOf(color)
      }
      return LegacyColor(
        red = Color.red(color) / 255f,
        green = Color.green(color) / 255f,
        blue = Color.blue(color) / 255f,
        alpha = Color.alpha(color) / 255f,
        argb = color,
      )
    }

    @JvmStatic
    fun valueOf(red: Float, green: Float, blue: Float, alpha: Float): Color {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return Api26Impl.valueOf(red, green, blue, alpha)
      }
      val saturatedRed = red.coerceIn(0f, 1f)
      val saturatedGreen = green.coerceIn(0f, 1f)
      val saturatedBlue = blue.coerceIn(0f, 1f)
      val saturatedAlpha = alpha.coerceIn(0f, 1f)
      return LegacyColor(
        red = saturatedRed,
        green = saturatedGreen,
        blue = saturatedBlue,
        alpha = saturatedAlpha,
        argb = Color.argb(
          toColorComponent(saturatedAlpha),
          toColorComponent(saturatedRed),
          toColorComponent(saturatedGreen),
          toColorComponent(saturatedBlue),
        ),
      )
    }

    @JvmStatic
    @ColorInt
    fun toArgb(color: Color): Int {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return Api26Impl.toArgb(color)
      }
      return legacyColor(color).argb
    }

    @JvmStatic
    fun alpha(color: Color): Float {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return Api26Impl.alpha(color)
      }
      return legacyColor(color).alpha
    }

    @JvmStatic
    fun red(color: Color): Float {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return Api26Impl.red(color)
      }
      return legacyColor(color).red
    }

    @JvmStatic
    fun green(color: Color): Float {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return Api26Impl.green(color)
      }
      return legacyColor(color).green
    }

    @JvmStatic
    fun blue(color: Color): Float {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return Api26Impl.blue(color)
      }
      return legacyColor(color).blue
    }

    /**
     * Below API 26 a `Color` holds nothing of its own: only one this class made has components to
     * read. Any other - one v1's `ColorCompat` made, or a bare `Color()` - is refused rather than
     * read as some color.
     */
    private fun legacyColor(color: Color): LegacyColor =
      color as? LegacyColor
        ?: throw IllegalArgumentException(
          "Below API 26 a Color has no components of its own, and this ${color.javaClass.name} " +
            "was not made by io.github.expo.modules.v2.react.ColorCompat - make it with " +
            "ColorCompat.valueOf",
        )

    private fun toColorComponent(component: Float): Int = (component * 255f + 0.5f).toInt()
  }

  private class LegacyColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
    @get:ColorInt val argb: Int,
  ) : Color() {
    override fun equals(other: Any?): Boolean =
      other is LegacyColor &&
        other.red == red &&
        other.green == green &&
        other.blue == blue &&
        other.alpha == alpha

    override fun hashCode(): Int {
      var result = red.hashCode()
      result = 31 * result + green.hashCode()
      result = 31 * result + blue.hashCode()
      result = 31 * result + alpha.hashCode()
      return result
    }

    override fun toString(): String =
      "Color($red, $green, $blue, $alpha)"
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private object Api26Impl {
    fun valueOf(@ColorInt color: Int): Color = Color.valueOf(color)

    fun valueOf(red: Float, green: Float, blue: Float, alpha: Float): Color =
      Color.valueOf(red, green, blue, alpha)

    fun toArgb(color: Color): Int = color.toArgb()

    fun alpha(color: Color): Float = color.alpha()

    fun red(color: Color): Float = color.red()

    fun green(color: Color): Float = color.green()

    fun blue(color: Color): Float = color.blue()
  }
}
