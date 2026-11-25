package io.github.expo.modules.v2.colors

/**
 * An sRGB color as four components in `0..1`, alpha last: what [ColorParser] reads a JavaScript
 * color value into. Platform code turns it into its own color type.
 */
internal data class Rgba(
  val red: Float,
  val green: Float,
  val blue: Float,
  val alpha: Float,
)

/**
 * Reads the color values JavaScript passes, the same ones Expo Modules v1's `ColorTypeConverter`
 * took, so a module keeps its JavaScript API when it moves to v2:
 *
 * - a number: a 32-bit ARGB color int, the form `processColor` produces
 * - a string: a CSS named color or `transparent`, `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA` (CSS
 *   byte order, alpha last), or an `rgb()`, `rgba()`, `hsl()`, `hsla()` or `hwb()` function
 * - an array: `[red, green, blue]` or `[red, green, blue, alpha]`, each in `0..1`
 *
 * It is plain Kotlin, so it lives here and is tested on the JVM; `:react` maps its result to
 * `android.graphics.Color`.
 */
internal object ColorParser {
  /** [value] as a color, or an [IllegalArgumentException] saying why it is not one. */
  fun parse(value: Any): Rgba = when (value) {
    is String -> parseString(value)
    is Number -> fromArgb(argbOf(value))
    is List<*> -> fromComponents(value)
    is DoubleArray -> fromComponents(value.toList())
    is Map<*, *> -> throw IllegalArgumentException(
      "A color object such as PlatformColor() is not supported - pass a number, a CSS color " +
        "string or an array of components",
    )
    else -> throw IllegalArgumentException(
      "A color is a number, a CSS color string or an array of components, not a " +
        value.javaClass.name,
    )
  }

  /**
   * The ARGB int a JavaScript number names. A JavaScript number is a double, so an ARGB written
   * as an unsigned literal such as `0xFFFF0000` is above [Int.MAX_VALUE]: it wraps to its low 32
   * bits instead of saturating.
   */
  fun argbOf(number: Number): Int {
    val double = number.toDouble()
    require(double.isFinite()) { "A color number must be finite, but got $double" }
    return double.toLong().toInt()
  }

  fun fromArgb(argb: Int): Rgba = rgba8(
    red = (argb shr 16) and 0xFF,
    green = (argb shr 8) and 0xFF,
    blue = argb and 0xFF,
    alpha = (argb ushr 24) and 0xFF,
  )

  /** [rgba] packed as an ARGB int, rounding each component the way `Color.toArgb` does. */
  fun toArgb(rgba: Rgba): Int =
    (toByte(rgba.alpha) shl 24) or (toByte(rgba.red) shl 16) or (toByte(rgba.green) shl 8) or
      toByte(rgba.blue)

  /** [argb] as `#rrggbbaa`: what a color crosses to JavaScript as, and what [parse] reads back. */
  fun toCssHex(argb: Int): String =
    "#%02x%02x%02x%02x".format(
      (argb shr 16) and 0xFF,
      (argb shr 8) and 0xFF,
      argb and 0xFF,
      (argb ushr 24) and 0xFF,
    )

  fun parseString(value: String): Rgba {
    val normalized = value.trim().lowercase()
    namedColors[normalized]?.let { return it }

    if (normalized.startsWith('#')) {
      return parseHex(normalized)
        ?: throw IllegalArgumentException("'$value' is not a hex color")
    }

    return parseCssFunction(normalized)
      ?: throw IllegalArgumentException("'$value' is not a color")
  }

  private fun fromComponents(components: List<*>): Rgba {
    require(components.size >= 3) {
      "A color components array must contain at least 3 values (red, green, blue), but got " +
        components.size
    }
    val numbers = components.take(4).mapIndexed { index, component ->
      (component as? Number)?.toFloat()
        ?: throw IllegalArgumentException(
          "Color component $index must be a number, but got ${component?.javaClass?.name}",
        )
    }
    return Rgba(
      red = numbers[0].coerceIn(0f, 1f),
      green = numbers[1].coerceIn(0f, 1f),
      blue = numbers[2].coerceIn(0f, 1f),
      alpha = numbers.getOrElse(3) { 1f }.coerceIn(0f, 1f),
    )
  }

  // region hex

  private val hex3 = Regex("""^#([0-9a-f])([0-9a-f])([0-9a-f])$""")
  private val hex4 = Regex("""^#([0-9a-f])([0-9a-f])([0-9a-f])([0-9a-f])$""")
  private val hex6 = Regex("""^#([0-9a-f]{6})$""")
  private val hex8 = Regex("""^#([0-9a-f]{8})$""")

  /** `#RGB`, `#RGBA`, `#RRGGBB` and `#RRGGBBAA`: CSS byte order, alpha last. */
  private fun parseHex(value: String): Rgba? {
    hex3.matchEntire(value)?.let { match ->
      val (r, g, b) = match.destructured
      return rgba8(doubled(r), doubled(g), doubled(b), 255)
    }

    hex4.matchEntire(value)?.let { match ->
      val (r, g, b, a) = match.destructured
      return rgba8(doubled(r), doubled(g), doubled(b), doubled(a))
    }

    hex6.matchEntire(value)?.let { match ->
      val rgb = match.groupValues[1].toInt(16)
      return rgba8((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
    }

    // Not Android's #AARRGGBB: like v1, eight digits are read in CSS order.
    hex8.matchEntire(value)?.let { match ->
      val rgba = match.groupValues[1].toLong(16)
      return rgba8(
        ((rgba shr 24) and 0xFF).toInt(),
        ((rgba shr 16) and 0xFF).toInt(),
        ((rgba shr 8) and 0xFF).toInt(),
        (rgba and 0xFF).toInt(),
      )
    }

    return null
  }

  private fun doubled(digit: String): Int = digit.repeat(2).toInt(16)

  // endregion

  // region CSS color functions

  // A signed or unsigned number with an optional fraction: "255", "-120", "+0.5", ".5".
  private const val NUM = """[-+]?\d*\.?\d+"""

  private val rgbCommaSeparated =
    Regex("""rgba?\(\s*($NUM%?)\s*,\s*($NUM%?)\s*,\s*($NUM%?)\s*(?:,\s*($NUM%?)\s*)?\)""")

  private val rgbSpaceSeparated =
    Regex("""rgba?\(\s*($NUM%?)\s+($NUM%?)\s+($NUM%?)\s*(?:/\s*($NUM%?)\s*)?\)""")

  private val hslCommaSeparated =
    Regex("""hsla?\(\s*($NUM)\s*,\s*($NUM)%\s*,\s*($NUM)%\s*(?:,\s*($NUM%?)\s*)?\)""")

  private val hslSpaceSeparated =
    Regex("""hsla?\(\s*($NUM)\s+($NUM)%\s+($NUM)%\s*(?:/\s*($NUM%?)\s*)?\)""")

  private val hwbPattern =
    Regex("""hwb\(\s*($NUM)\s+($NUM)%\s+($NUM)%\s*(?:/\s*($NUM%?)\s*)?\)""")

  /** `rgb()`, `rgba()`, `hsl()`, `hsla()` and `hwb()`, or null when [value] is none of them. */
  private fun parseCssFunction(value: String): Rgba? {
    (rgbCommaSeparated.matchEntire(value) ?: rgbSpaceSeparated.matchEntire(value))?.let { match ->
      return Rgba(
        red = parseRgbComponent(match.groupValues[1]),
        green = parseRgbComponent(match.groupValues[2]),
        blue = parseRgbComponent(match.groupValues[3]),
        alpha = parseAlpha(match.groupValues[4]),
      )
    }

    (hslCommaSeparated.matchEntire(value) ?: hslSpaceSeparated.matchEntire(value))?.let { match ->
      return hslToRgba(
        hue = match.groupValues[1].toFloat(),
        saturation = (match.groupValues[2].toFloat() / 100f).coerceIn(0f, 1f),
        lightness = (match.groupValues[3].toFloat() / 100f).coerceIn(0f, 1f),
        alpha = parseAlpha(match.groupValues[4]),
      )
    }

    hwbPattern.matchEntire(value)?.let { match ->
      return hwbToRgba(
        hue = match.groupValues[1].toFloat(),
        whiteness = match.groupValues[2].toFloat() / 100f,
        blackness = match.groupValues[3].toFloat() / 100f,
        alpha = parseAlpha(match.groupValues[4]),
      )
    }

    return null
  }

  /** A `0..255` number or a `0..100%` percentage, as a component in `0..1`. */
  private fun parseRgbComponent(value: String): Float =
    if (value.endsWith("%")) {
      value.dropLast(1).toFloat() / 100f
    } else {
      value.toFloat() / 255f
    }.coerceIn(0f, 1f)

  /** A `0..1` number or a `0..100%` percentage; opaque when the function leaves it out. */
  private fun parseAlpha(value: String): Float {
    if (value.isEmpty()) {
      return 1f
    }
    return if (value.endsWith("%")) {
      value.dropLast(1).toFloat() / 100f
    } else {
      value.toFloat()
    }.coerceIn(0f, 1f)
  }

  private fun hueToRgb(p: Float, q: Float, t: Float): Float {
    val tt = when {
      t < 0f -> t + 1f
      t > 1f -> t - 1f
      else -> t
    }
    return when {
      tt < 1f / 6f -> p + (q - p) * 6f * tt
      tt < 1f / 2f -> q
      tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
      else -> p
    }
  }

  private fun hslToRgba(hue: Float, saturation: Float, lightness: Float, alpha: Float): Rgba {
    val h = ((hue % 360f) + 360f) % 360f / 360f
    if (saturation == 0f) {
      return Rgba(lightness, lightness, lightness, alpha)
    }
    val q = if (lightness < 0.5f) {
      lightness * (1f + saturation)
    } else {
      lightness + saturation - lightness * saturation
    }
    val p = 2f * lightness - q
    return Rgba(
      red = hueToRgb(p, q, h + 1f / 3f).coerceIn(0f, 1f),
      green = hueToRgb(p, q, h).coerceIn(0f, 1f),
      blue = hueToRgb(p, q, h - 1f / 3f).coerceIn(0f, 1f),
      alpha = alpha,
    )
  }

  private fun hwbToRgba(hue: Float, whiteness: Float, blackness: Float, alpha: Float): Rgba {
    val w = whiteness.coerceIn(0f, 1f)
    val b = blackness.coerceIn(0f, 1f)
    // When they add up to more than 100%, CSS scales both down to a gray.
    val sum = w + b
    val white = if (sum > 1f) w / sum else w
    val black = if (sum > 1f) b / sum else b
    val pure = hslToRgba(hue, 1f, 0.5f, 1f)
    val scale = 1f - white - black
    return Rgba(
      red = (pure.red * scale + white).coerceIn(0f, 1f),
      green = (pure.green * scale + white).coerceIn(0f, 1f),
      blue = (pure.blue * scale + white).coerceIn(0f, 1f),
      alpha = alpha,
    )
  }

  // endregion

  private fun toByte(component: Float): Int = (component.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

  /**
   * The [CSS3/SVG named colors](https://www.w3.org/TR/css-color-3/#svg-color) and `transparent`,
   * as v1 lists them.
   */
  private val namedColors: Map<String, Rgba> = mapOf(
    "aliceblue" to rgba8(240, 248, 255, 255),
    "antiquewhite" to rgba8(250, 235, 215, 255),
    "aqua" to rgba8(0, 255, 255, 255),
    "aquamarine" to rgba8(127, 255, 212, 255),
    "azure" to rgba8(240, 255, 255, 255),
    "beige" to rgba8(245, 245, 220, 255),
    "bisque" to rgba8(255, 228, 196, 255),
    "black" to rgba8(0, 0, 0, 255),
    "blanchedalmond" to rgba8(255, 235, 205, 255),
    "blue" to rgba8(0, 0, 255, 255),
    "blueviolet" to rgba8(138, 43, 226, 255),
    "brown" to rgba8(165, 42, 42, 255),
    "burlywood" to rgba8(222, 184, 135, 255),
    "cadetblue" to rgba8(95, 158, 160, 255),
    "chartreuse" to rgba8(127, 255, 0, 255),
    "chocolate" to rgba8(210, 105, 30, 255),
    "coral" to rgba8(255, 127, 80, 255),
    "cornflowerblue" to rgba8(100, 149, 237, 255),
    "cornsilk" to rgba8(255, 248, 220, 255),
    "crimson" to rgba8(220, 20, 60, 255),
    "cyan" to rgba8(0, 255, 255, 255),
    "darkblue" to rgba8(0, 0, 139, 255),
    "darkcyan" to rgba8(0, 139, 139, 255),
    "darkgoldenrod" to rgba8(184, 134, 11, 255),
    "darkgray" to rgba8(169, 169, 169, 255),
    "darkgreen" to rgba8(0, 100, 0, 255),
    "darkgrey" to rgba8(169, 169, 169, 255),
    "darkkhaki" to rgba8(189, 183, 107, 255),
    "darkmagenta" to rgba8(139, 0, 139, 255),
    "darkolivegreen" to rgba8(85, 107, 47, 255),
    "darkorange" to rgba8(255, 140, 0, 255),
    "darkorchid" to rgba8(153, 50, 204, 255),
    "darkred" to rgba8(139, 0, 0, 255),
    "darksalmon" to rgba8(233, 150, 122, 255),
    "darkseagreen" to rgba8(143, 188, 143, 255),
    "darkslateblue" to rgba8(72, 61, 139, 255),
    "darkslategray" to rgba8(47, 79, 79, 255),
    "darkslategrey" to rgba8(47, 79, 79, 255),
    "darkturquoise" to rgba8(0, 206, 209, 255),
    "darkviolet" to rgba8(148, 0, 211, 255),
    "deeppink" to rgba8(255, 20, 147, 255),
    "deepskyblue" to rgba8(0, 191, 255, 255),
    "dimgray" to rgba8(105, 105, 105, 255),
    "dimgrey" to rgba8(105, 105, 105, 255),
    "dodgerblue" to rgba8(30, 144, 255, 255),
    "firebrick" to rgba8(178, 34, 34, 255),
    "floralwhite" to rgba8(255, 250, 240, 255),
    "forestgreen" to rgba8(34, 139, 34, 255),
    "fuchsia" to rgba8(255, 0, 255, 255),
    "gainsboro" to rgba8(220, 220, 220, 255),
    "ghostwhite" to rgba8(248, 248, 255, 255),
    "gold" to rgba8(255, 215, 0, 255),
    "goldenrod" to rgba8(218, 165, 32, 255),
    "gray" to rgba8(128, 128, 128, 255),
    "green" to rgba8(0, 128, 0, 255),
    "greenyellow" to rgba8(173, 255, 47, 255),
    "grey" to rgba8(128, 128, 128, 255),
    "honeydew" to rgba8(240, 255, 240, 255),
    "hotpink" to rgba8(255, 105, 180, 255),
    "indianred" to rgba8(205, 92, 92, 255),
    "indigo" to rgba8(75, 0, 130, 255),
    "ivory" to rgba8(255, 255, 240, 255),
    "khaki" to rgba8(240, 230, 140, 255),
    "lavender" to rgba8(230, 230, 250, 255),
    "lavenderblush" to rgba8(255, 240, 245, 255),
    "lawngreen" to rgba8(124, 252, 0, 255),
    "lemonchiffon" to rgba8(255, 250, 205, 255),
    "lightblue" to rgba8(173, 216, 230, 255),
    "lightcoral" to rgba8(240, 128, 128, 255),
    "lightcyan" to rgba8(224, 255, 255, 255),
    "lightgoldenrodyellow" to rgba8(250, 250, 210, 255),
    "lightgray" to rgba8(211, 211, 211, 255),
    "lightgreen" to rgba8(144, 238, 144, 255),
    "lightgrey" to rgba8(211, 211, 211, 255),
    "lightpink" to rgba8(255, 182, 193, 255),
    "lightsalmon" to rgba8(255, 160, 122, 255),
    "lightseagreen" to rgba8(32, 178, 170, 255),
    "lightskyblue" to rgba8(135, 206, 250, 255),
    "lightslategray" to rgba8(119, 136, 153, 255),
    "lightslategrey" to rgba8(119, 136, 153, 255),
    "lightsteelblue" to rgba8(176, 196, 222, 255),
    "lightyellow" to rgba8(255, 255, 224, 255),
    "lime" to rgba8(0, 255, 0, 255),
    "limegreen" to rgba8(50, 205, 50, 255),
    "linen" to rgba8(250, 240, 230, 255),
    "magenta" to rgba8(255, 0, 255, 255),
    "maroon" to rgba8(128, 0, 0, 255),
    "mediumaquamarine" to rgba8(102, 205, 170, 255),
    "mediumblue" to rgba8(0, 0, 205, 255),
    "mediumorchid" to rgba8(186, 85, 211, 255),
    "mediumpurple" to rgba8(147, 112, 219, 255),
    "mediumseagreen" to rgba8(60, 179, 113, 255),
    "mediumslateblue" to rgba8(123, 104, 238, 255),
    "mediumspringgreen" to rgba8(0, 250, 154, 255),
    "mediumturquoise" to rgba8(72, 209, 204, 255),
    "mediumvioletred" to rgba8(199, 21, 133, 255),
    "midnightblue" to rgba8(25, 25, 112, 255),
    "mintcream" to rgba8(245, 255, 250, 255),
    "mistyrose" to rgba8(255, 228, 225, 255),
    "moccasin" to rgba8(255, 228, 181, 255),
    "navajowhite" to rgba8(255, 222, 173, 255),
    "navy" to rgba8(0, 0, 128, 255),
    "oldlace" to rgba8(253, 245, 230, 255),
    "olive" to rgba8(128, 128, 0, 255),
    "olivedrab" to rgba8(107, 142, 35, 255),
    "orange" to rgba8(255, 165, 0, 255),
    "orangered" to rgba8(255, 69, 0, 255),
    "orchid" to rgba8(218, 112, 214, 255),
    "palegoldenrod" to rgba8(238, 232, 170, 255),
    "palegreen" to rgba8(152, 251, 152, 255),
    "paleturquoise" to rgba8(175, 238, 238, 255),
    "palevioletred" to rgba8(219, 112, 147, 255),
    "papayawhip" to rgba8(255, 239, 213, 255),
    "peachpuff" to rgba8(255, 218, 185, 255),
    "peru" to rgba8(205, 133, 63, 255),
    "pink" to rgba8(255, 192, 203, 255),
    "plum" to rgba8(221, 160, 221, 255),
    "powderblue" to rgba8(176, 224, 230, 255),
    "purple" to rgba8(128, 0, 128, 255),
    "rebeccapurple" to rgba8(102, 51, 153, 255),
    "red" to rgba8(255, 0, 0, 255),
    "rosybrown" to rgba8(188, 143, 143, 255),
    "royalblue" to rgba8(65, 105, 225, 255),
    "saddlebrown" to rgba8(139, 69, 19, 255),
    "salmon" to rgba8(250, 128, 114, 255),
    "sandybrown" to rgba8(244, 164, 96, 255),
    "seagreen" to rgba8(46, 139, 87, 255),
    "seashell" to rgba8(255, 245, 238, 255),
    "sienna" to rgba8(160, 82, 45, 255),
    "silver" to rgba8(192, 192, 192, 255),
    "skyblue" to rgba8(135, 206, 235, 255),
    "slateblue" to rgba8(106, 90, 205, 255),
    "slategray" to rgba8(112, 128, 144, 255),
    "slategrey" to rgba8(112, 128, 144, 255),
    "snow" to rgba8(255, 250, 250, 255),
    "springgreen" to rgba8(0, 255, 127, 255),
    "steelblue" to rgba8(70, 130, 180, 255),
    "tan" to rgba8(210, 180, 140, 255),
    "teal" to rgba8(0, 128, 128, 255),
    "thistle" to rgba8(216, 191, 216, 255),
    "tomato" to rgba8(255, 99, 71, 255),
    "transparent" to rgba8(0, 0, 0, 0),
    "turquoise" to rgba8(64, 224, 208, 255),
    "violet" to rgba8(238, 130, 238, 255),
    "wheat" to rgba8(245, 222, 179, 255),
    "white" to rgba8(255, 255, 255, 255),
    "whitesmoke" to rgba8(245, 245, 245, 255),
    "yellow" to rgba8(255, 255, 0, 255),
    "yellowgreen" to rgba8(154, 205, 50, 255)
  )
}

private fun rgba8(red: Int, green: Int, blue: Int, alpha: Int): Rgba =
  Rgba(red / 255f, green / 255f, blue / 255f, alpha / 255f)
