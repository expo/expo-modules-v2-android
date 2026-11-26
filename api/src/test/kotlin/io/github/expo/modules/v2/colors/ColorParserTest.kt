package io.github.expo.modules.v2.colors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pins what `ColorParser` reads: the inputs Expo Modules v1's `ColorTypeConverter` took. */
class ColorParserTest {
  /** [value] parsed and packed as `#rrggbbaa`, so expectations read like CSS. */
  private fun hex(value: Any): String = ColorParser.toCssHex(ColorParser.toArgb(ColorParser.parse(value)))

  @Test
  fun `named colors, in any case and with surrounding whitespace`() {
    assertEquals("#ff0000ff", hex("red"))
    assertEquals("#f0f8ffff", hex("aliceblue"))
    assertEquals("#663399ff", hex("RebeccaPurple"))
    assertEquals("#9acd32ff", hex("  yellowgreen \n"))
    assertEquals("#808080ff", hex("grey"))
    assertEquals("#808080ff", hex("gray"))
    assertEquals("#00000000", hex("transparent"))
  }

  @Test
  fun `hex colors of every length, in CSS byte order`() {
    assertEquals("#ff0000ff", hex("#f00"))
    assertEquals("#aabbccff", hex("#ABC"))
    assertEquals("#ff000088", hex("#f008"))
    assertEquals("#12abefff", hex("#12ABEF"))
    // Eight digits are RRGGBBAA, not Android's AARRGGBB.
    assertEquals("#11223344", hex("#11223344"))
  }

  @Test
  fun `rgb and rgba, comma and space separated, with numbers and percentages`() {
    assertEquals("#ff8000ff", hex("rgb(255, 128, 0)"))
    assertEquals("#ff8000ff", hex("rgb(255 128 0)"))
    assertEquals("#ff800080", hex("rgba(255, 128, 0, 0.5)"))
    assertEquals("#ff800080", hex("rgb(255 128 0 / 50%)"))
    assertEquals("#ff8000ff", hex("RGB(100%, 50.2%, 0%)"))
    assertEquals("#0000ff00", hex("rgba(0, 0, 255, 0)"))
    assertEquals("#80808040", hex("rgba(128,128,128,.25)"))
  }

  @Test
  fun `whitespace CSS allows inside a function`() {
    assertEquals("#ff000080", hex("rgb(255 0 0 / 50% )"))
    assertEquals("#ff0000ff", hex("rgb( 255 0 0 )"))
    assertEquals("#ff000080", hex("rgb(255 0 0/50%)"))
    assertEquals("#ff000080", hex("rgba( 255 , 0 , 0 , 0.5 )"))
    assertEquals("#ff0000ff", hex("rgb(\t255\n0 0 )"))
    assertEquals("#ff000080", hex("hsl(0 100% 50% / 50% )"))
    assertEquals("#ff0000ff", hex("hsl( 0 , 100% , 50% )"))
    assertEquals("#ff000080", hex("hwb( 0 0% 0% / 0.5 )"))
    // But not between the name and the parenthesis, or between a number and its unit.
    assertFailsWith<IllegalArgumentException> { ColorParser.parse("rgb (255, 0, 0)") }
    assertFailsWith<IllegalArgumentException> { ColorParser.parse("hsl(0, 100 %, 50%)") }
  }

  @Test
  fun `rgb components out of range are clamped`() {
    assertEquals("#ff0000ff", hex("rgb(300, -20, 0)"))
    assertEquals("#000000ff", hex("rgba(0, 0, 0, 2)"))
  }

  @Test
  fun `hsl and hsla`() {
    assertEquals("#ff0000ff", hex("hsl(0, 100%, 50%)"))
    assertEquals("#00ff00ff", hex("hsl(120 100% 50%)"))
    assertEquals("#0000ffff", hex("hsl(240, 100%, 50%)"))
    // A hue wraps around the circle, negative ones too.
    assertEquals("#ff0000ff", hex("hsl(360, 100%, 50%)"))
    assertEquals("#0000ffff", hex("hsl(-120, 100%, 50%)"))
    assertEquals("#808080ff", hex("hsl(200, 0%, 50.2%)"))
    assertEquals("#ffffffff", hex("hsl(0, 0%, 100%)"))
    assertEquals("#ff000080", hex("hsla(0, 100%, 50%, 0.5)"))
    assertEquals("#00ff0040", hex("hsl(120 100% 50% / 25%)"))
  }

  @Test
  fun `hwb`() {
    assertEquals("#ff0000ff", hex("hwb(0 0% 0%)"))
    assertEquals("#ffffffff", hex("hwb(0 100% 0%)"))
    assertEquals("#000000ff", hex("hwb(0 0% 100%)"))
    // Whiteness and blackness above 100% together scale down to a gray.
    assertEquals("#808080ff", hex("hwb(120 50% 50%)"))
    assertEquals("#808080ff", hex("hwb(120 100% 100%)"))
    assertEquals("#00ff0080", hex("hwb(120 0% 0% / 0.5)"))
  }

  @Test
  fun `numbers are ARGB ints`() {
    assertEquals("#11223344", hex(0x44112233))
    // processColor's signed form and the unsigned literal JavaScript writes name the same color.
    assertEquals("#ff0000ff", hex(0xFFFF0000.toInt()))
    assertEquals("#ff0000ff", hex(4294901760.0))
    assertEquals("#ff0000ff", hex(-65536.0))
    assertEquals("#00000000", hex(0.0))
    // The ends of the signed and the unsigned range.
    assertEquals("#00000080", hex(-2147483648.0))
    assertEquals("#ffffffff", hex(4294967295.0))
    assertEquals("#ffffffff", hex(-1L))
  }

  @Test
  fun `a number that is no 32-bit ARGB value is refused`() {
    listOf(
      Double.NaN,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      0.5,
      -0.25,
      4294967296.0, // 2^32
      -2147483649.0, // -2^31 - 1
      9.223372036854775807E18, // 2^63
      1095216726015.0, // 0xFF0000FFFF
      0x1_0000_0000L,
    ).forEach { value ->
      assertFailsWith<IllegalArgumentException>("$value must be refused") { ColorParser.parse(value) }
    }
  }

  @Test
  fun `arrays are red, green, blue and an optional alpha in 0 to 1`() {
    assertEquals("#ff8000ff", hex(listOf(1.0, 0.502, 0.0)))
    assertEquals("#ff800080", hex(listOf(1.0, 0.502, 0.0, 0.5)))
    assertEquals("#ff800080", hex(doubleArrayOf(1.0, 0.502, 0.0, 0.5)))
    // Out-of-range components are clamped and anything past alpha is ignored.
    assertEquals("#ff0000ff", hex(listOf(2.0, -1.0, 0.0, 1.0, 0.3)))
  }

  @Test
  fun `an array needs three numbers`() {
    assertFailsWith<IllegalArgumentException> { ColorParser.parse(listOf(1.0, 0.0)) }
    assertFailsWith<IllegalArgumentException> { ColorParser.parse(listOf(1.0, "0", 0.0)) }
    assertFailsWith<IllegalArgumentException> { ColorParser.parse(listOf(1.0, null, 0.0)) }
  }

  @Test
  fun `a component that is not finite is refused rather than clamped`() {
    listOf(
      listOf(Double.NaN, 0.0, 0.0),
      listOf(0.0, Double.POSITIVE_INFINITY, 0.0),
      listOf(0.0, 0.0, 0.0, Double.NEGATIVE_INFINITY),
    ).forEach { value ->
      assertFailsWith<IllegalArgumentException>("$value must be refused") { ColorParser.parse(value) }
    }
  }

  @Test
  fun `what is not a color is refused`() {
    listOf(
      "",
      "notacolor",
      "#",
      "#12",
      "#12345",
      "#1234567",
      "#123456789",
      "#ggg",
      "rgb(1, 2)",
      "rgb(a, b, c)",
      "hsl(0, 100, 50)",
      "hwb(0, 0%, 0%)",
      "red blue",
    ).forEach { value ->
      assertFailsWith<IllegalArgumentException>("'$value' must be refused") {
        ColorParser.parse(value)
      }
    }
  }

  @Test
  fun `a map such as PlatformColor is refused with a reason`() {
    val error = assertFailsWith<IllegalArgumentException> {
      ColorParser.parse(mapOf("resource_paths" to listOf("?attr/colorPrimary")))
    }
    assertTrue("PlatformColor" in error.message.orEmpty(), error.message)
    assertFailsWith<IllegalArgumentException> { ColorParser.parse(true) }
  }

  @Test
  fun `every color crosses back as a hex string that parses to itself`() {
    listOf(0, -1, 0x80FF8000.toInt(), 0x00ABCDEF, 0x7F000001).forEach { argb ->
      val css = ColorParser.toCssHex(argb)
      assertTrue(Regex("^#[0-9a-f]{8}$").matches(css), css)
      assertEquals(argb, ColorParser.toArgb(ColorParser.parse(css)), css)
    }
  }

  @Test
  fun `components pack into ARGB the way Color toArgb rounds them`() {
    assertEquals(0x80FF0000.toInt(), ColorParser.toArgb(Rgba(1f, 0f, 0f, 0.5f)))
    assertEquals(0xFF808080.toInt(), ColorParser.toArgb(Rgba(0.5f, 0.5f, 0.5f, 1f)))
    assertEquals(0x00000000, ColorParser.toArgb(Rgba(-1f, 0f, 0f, 0f)))
  }
}
