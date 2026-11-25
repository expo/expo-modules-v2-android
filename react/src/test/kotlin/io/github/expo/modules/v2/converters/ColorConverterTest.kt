package io.github.expo.modules.v2.converters

import android.graphics.Color
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Union
import io.github.expo.modules.v2.binary.BinaryValueCodec
import io.github.expo.modules.v2.colors.ColorParser
import io.github.expo.modules.v2.colors.Rgba
import io.github.expo.modules.v2.react.ColorCompat
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.unions.UnionValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URL
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class Preset { PRIMARY, SECONDARY }

private enum class Level(val value: Int) { LOW(0), HIGH(1) }

/** A string enum first: native code offers it every string, and it refuses a CSS color. */
@Union
private sealed interface PresetOrColor {
  @JvmInline
  value class Named(val preset: Preset) : PresetOrColor

  @JvmInline
  value class Custom(val color: Color) : PresetOrColor
}

/** An Int-backed enum first: it refuses an ARGB number that is no entry. */
@Union
private sealed interface LevelOrColor {
  @JvmInline
  value class Named(val level: Level) : LevelOrColor

  @JvmInline
  value class Custom(val color: Color) : LevelOrColor
}

@Union
private sealed interface UrlOrColor {
  @JvmInline
  value class Address(val url: URL) : UrlOrColor

  @JvmInline
  value class Custom(val color: Color) : UrlOrColor
}

/** A Color first, then `Any` with its own type codes: what the Color refuses goes on to `Any`. */
@Union
private sealed interface ColorOrAny {
  @JvmInline
  value class Custom(val color: Color) : ColorOrAny

  @JvmInline
  value class Other(val value: Any) : ColorOrAny
}

/** `ColorConverter` and `ColorCompat` on a real Android framework, at the pinned SDK unless noted. */
@RunWith(RobolectricTestRunner::class)
class ColorConverterTest {
  private val converter = ColorConverter(isNullable = false)
  private val nullableConverter = ColorConverter(isNullable = true)

  private fun argbOf(color: Any?): Int = ColorCompat.toArgb(color as Color)

  /** A resolved converter, typed so that values can be handed to it. */
  @Suppress("UNCHECKED_CAST")
  private val TypeConverter<*>.untyped: TypeConverter<Any?> get() = this as TypeConverter<Any?>

  private fun TypeConverter<*>.roundTripBuffer(value: Any?): Any? {
    val buffer = BinaryBuffer.allocate(4 * 1024)
    val writer = buffer.duplicateView()
    untyped.writeToBuffer(writer, value)
    val reader = buffer.duplicateView().apply { limit = writer.position }
    return readFromBuffer(reader).also {
      assertEquals(writer.position, reader.position, "the read must consume what was written")
    }
  }

  /** What native code puts on the buffer for a dynamic value: [value], tagged. */
  private fun bufferHolding(value: Any?, presence: Boolean? = null): BinaryBuffer {
    val buffer = BinaryBuffer.allocate(256)
    val writer = buffer.duplicateView()
    presence?.let { writer.putBoolean(it) }
    if (presence != false) {
      BinaryValueCodec.writeDynamic(writer, value)
    }
    return buffer.duplicateView().apply { limit = writer.position }
  }

  @Test
  fun `the registry resolves Color to ColorConverter, crossing as a dynamic value`() {
    val plain = TypeDescriptor.Simple(Color::class.java, false).converter
    val nullable = TypeDescriptor.Simple(Color::class.java, true).converter
    assertIs<ColorConverter>(plain)
    assertIs<ColorConverter>(nullable)
    assertNotSame(plain, nullable)
    assertContentEquals(intArrayOf(CppType.ANY.code), plain.typeCodes())
    assertContentEquals(intArrayOf(CppType.ANY.nullableCode()), nullable.typeCodes())
  }

  @Test
  fun `fromJni reads every bridge value JavaScript passes`() {
    // A JavaScript number arrives as a Double: signed, as processColor gives it, or unsigned.
    assertEquals(0xFFFF0000.toInt(), argbOf(converter.fromJni(-65536.0)))
    assertEquals(0xFFFF0000.toInt(), argbOf(converter.fromJni(4294901760.0)))
    assertEquals(0x80FF0000.toInt(), argbOf(converter.fromJni("#ff000080")))
    assertEquals(0xFF0080FF.toInt(), argbOf(converter.fromJni("rgb(0 128 255)")))
    assertEquals(0xFF663399.toInt(), argbOf(converter.fromJni("rebeccapurple")))
    // An array arrives as an ArrayList of Doubles.
    assertEquals(0x80FF0000.toInt(), argbOf(converter.fromJni(arrayListOf(1.0, 0.0, 0.0, 0.5))))
  }

  @Test
  fun `toJni writes #rrggbbaa, which reads back to the same color`() {
    assertEquals("#ff000080", converter.toJni(Color.valueOf(0x80FF0000.toInt())))
    assertNull(nullableConverter.toJni(null))

    listOf("red", "#0f08", "hsl(120 100% 50% / 25%)", "hwb(240 10% 10%)", "transparent").forEach { css ->
      val out = converter.toJni(converter.fromJni(css))
      assertEquals(argbOf(converter.fromJni(css)), argbOf(converter.fromJni(out)), css)
      assertTrue(Regex("^#[0-9a-f]{8}$").matches(out as String), out)
    }
    listOf(0, -1, 0x12345678, 0x80FF0000.toInt()).forEach { argb ->
      assertEquals(argb, argbOf(converter.fromJni(converter.toJni(Color.valueOf(argb)))))
    }
  }

  @Test
  fun `null and what is not a color are refused`() {
    assertNull(nullableConverter.fromJni(null))
    assertFailsWith<IllegalArgumentException> { converter.fromJni(null) }
    assertFailsWith<IllegalArgumentException> { converter.fromJni("notacolor") }
    assertFailsWith<IllegalArgumentException> { converter.fromJni(0.5) }
    assertFailsWith<IllegalArgumentException> { converter.fromJni(arrayListOf(Double.NaN, 0.0, 0.0)) }
    val platformColor = assertFailsWith<IllegalArgumentException> {
      converter.fromJni(hashMapOf("resource_paths" to arrayListOf("?attr/colorPrimary")))
    }
    assertTrue("PlatformColor" in platformColor.message.orEmpty(), platformColor.message)
  }

  @Test
  fun `the buffer carries a tagged value, behind a presence byte when nullable`() {
    val red = Color.valueOf(0xFFFF0000.toInt())
    assertEquals(red, converter.roundTripBuffer(red))
    assertEquals(red, nullableConverter.roundTripBuffer(red))
    assertNull(nullableConverter.roundTripBuffer(null))

    // What native code writes for a JavaScript string, a number or an array.
    assertEquals(0xFF00FF00.toInt(), argbOf(converter.readFromBuffer(bufferHolding("#00ff00"))))
    assertEquals(0xFFFF0000.toInt(), argbOf(converter.readFromBuffer(bufferHolding(-65536.0))))
    assertEquals(
      0xFF0000FF.toInt(),
      argbOf(converter.readFromBuffer(bufferHolding(listOf(0.0, 0.0, 1.0)))),
    )
    assertEquals(
      0xFF00FF00.toInt(),
      argbOf(nullableConverter.readFromBuffer(bufferHolding("#00ff00", presence = true))),
    )
    assertNull(nullableConverter.readFromBuffer(bufferHolding(null, presence = false)))

    // A color crosses back as its #rrggbbaa string.
    val buffer = BinaryBuffer.allocate(256)
    val writer = buffer.duplicateView()
    converter.writeToBuffer(writer, red)
    assertEquals("#ff0000ff", BinaryValueCodec.readDynamic(buffer.duplicateView().apply { limit = writer.position }))
  }

  @Test
  fun `a List of colors round trips through the buffer`() {
    val list = TypeDescriptor.Parametrized(
      List::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(Color::class.java, false)),
    ).converter
    val colors = listOf(Color.valueOf(0xFFFF0000.toInt()), Color.valueOf(0x8000FF00.toInt()))
    assertEquals(colors, list.roundTripBuffer(colors))
  }

  @Test
  fun `a CSS color a string enum refuses goes on to a later Color`() {
    val union = TypeDescriptor.Simple(PresetOrColor::class.java, false).converter
    assertEquals(PresetOrColor.Named(Preset.SECONDARY), union.fromJni(UnionValue(0, "SECONDARY")))
    val custom = assertIs<PresetOrColor.Custom>(union.fromJni(UnionValue(0, "#ff0000")))
    assertEquals(0xFFFF0000.toInt(), argbOf(custom.color))
    // Not a color either: the Color's refusal is the one reported.
    assertFailsWith<IllegalArgumentException> { union.fromJni(UnionValue(0, "neither")) }
  }

  @Test
  fun `an ARGB number an Int-backed enum refuses goes on to a later Color`() {
    val union = TypeDescriptor.Simple(LevelOrColor::class.java, false).converter
    assertEquals(LevelOrColor.Named(Level.HIGH), union.fromJni(UnionValue(0, 1)))
    // The enum's bridge is an Int, so that is what the Color gets.
    val custom = assertIs<LevelOrColor.Custom>(union.fromJni(UnionValue(0, -65536)))
    assertEquals(0xFFFF0000.toInt(), argbOf(custom.color))
  }

  @Test
  fun `a string a URL refuses goes on to a later Color`() {
    val union = TypeDescriptor.Simple(UrlOrColor::class.java, false).converter
    assertEquals(UrlOrColor.Address(URL("https://expo.dev")), union.fromJni(UnionValue(0, "https://expo.dev")))
    val custom = assertIs<UrlOrColor.Custom>(union.fromJni(UnionValue(0, "red")))
    assertEquals(0xFFFF0000.toInt(), argbOf(custom.color))
  }

  @Test
  fun `what a Color refuses goes on to a later Any`() {
    val union = TypeDescriptor.Simple(ColorOrAny::class.java, false).converter
    val custom = assertIs<ColorOrAny.Custom>(union.fromJni(UnionValue(0, "#00ff00")))
    assertEquals(0xFF00FF00.toInt(), argbOf(custom.color))
    assertEquals(ColorOrAny.Other("notacolor"), union.fromJni(UnionValue(0, "notacolor")))
    val map = hashMapOf("resource_paths" to arrayListOf("?attr/colorPrimary"))
    assertEquals(ColorOrAny.Other(map), union.fromJni(UnionValue(0, map)))
  }

  @Test
  fun `ColorParser packs components the way Color toArgb rounds them`() {
    val steps = listOf(0f, 0.001f, 0.25f, 0.4f, 0.5f, 0.501f, 0.6f, 0.749f, 0.998f, 1f) +
      (0..255).map { it / 255f } + (0..20).map { it / 20f + 0.0001f }.filter { it <= 1f }
    for (component in steps) {
      for ((r, g, b, a) in listOf(
        listOf(component, 0f, 0f, 1f),
        listOf(0f, component, 1f - component, component),
        listOf(component, component, component, 1f - component),
      )) {
        assertEquals(
          Color.valueOf(r, g, b, a).toArgb(),
          ColorParser.toArgb(Rgba(r, g, b, a)),
          "($r, $g, $b, $a)",
        )
      }
    }
  }

  @Test
  @Config(sdk = [24])
  fun `below API 26 ColorCompat makes and reads its own colors`() {
    val fromInt = ColorCompat.valueOf(0x80FF0000.toInt())
    assertNotEquals(Color::class.java, fromInt.javaClass, "below API 26 Color.valueOf does not exist")
    assertEquals(0x80FF0000.toInt(), ColorCompat.toArgb(fromInt))
    assertEquals(1f, ColorCompat.red(fromInt))
    assertEquals(0f, ColorCompat.green(fromInt))
    assertEquals(0f, ColorCompat.blue(fromInt))
    assertEquals(128 / 255f, ColorCompat.alpha(fromInt))

    val fromComponents = ColorCompat.valueOf(2f, 0.5f, -1f, 1f)
    assertEquals(0xFFFF8000.toInt(), ColorCompat.toArgb(fromComponents), "components are clamped")
    assertEquals(ColorCompat.valueOf(1f, 0.5f, 0f, 1f), fromComponents, "equal by components")
    assertEquals(ColorCompat.valueOf(1f, 0.5f, 0f, 1f).hashCode(), fromComponents.hashCode())
    assertNotEquals(ColorCompat.valueOf(1f, 0.5f, 0f, 0.5f), fromComponents)

    // The converter goes through ColorCompat, both ways.
    val parsed = converter.fromJni("#ff000080")
    assertEquals(0x80FF0000.toInt(), argbOf(parsed))
    assertEquals("#ff000080", converter.toJni(parsed))
    assertEquals(parsed, converter.roundTripBuffer(parsed))

    // A Color it did not make - v1's ColorCompat's, or a bare one - has no components to read.
    val foreign = Color()
    val error = assertFailsWith<IllegalArgumentException> { ColorCompat.toArgb(foreign) }
    assertTrue("ColorCompat" in error.message.orEmpty(), error.message)
    listOf<(Color) -> Float>(
      { ColorCompat.red(it) },
      { ColorCompat.green(it) },
      { ColorCompat.blue(it) },
      { ColorCompat.alpha(it) },
    ).forEach { read ->
      assertFailsWith<IllegalArgumentException> { read(foreign) }
    }
    assertFailsWith<IllegalArgumentException> { converter.toJni(foreign) }
  }
}
