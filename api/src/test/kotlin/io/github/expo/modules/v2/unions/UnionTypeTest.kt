package io.github.expo.modules.v2.unions

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.Tag
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The annotation, not the marker interface of this package.
@io.github.expo.modules.v2.Union
private sealed interface Dimension {
  @JvmInline
  value class Points(val value: Double) : Dimension

  @Tag("auto")
  data object Auto : Dimension

  @Record
  data class Percent(val percent: Double) : Dimension
}

@io.github.expo.modules.v2.Union(discriminator = "type")
private sealed interface Shape {
  @Record
  @Tag("circle")
  data class Circle(val radius: Double) : Shape

  @Record
  data class Square(val side: Double) : Shape
}

/** What a hand-written codec looks like: the compiler plugin would reject every union below. */
private sealed interface Handwritten {
  data class First(val value: Any?) : Handwritten

  data class Second(val value: Any?) : Handwritten
}

private class HandwrittenCodec(
  name: String,
  discriminator: String,
  first: TypeDescriptor?,
  second: TypeDescriptor?,
  firstTag: String = "First",
  secondTag: String = "Second",
) : UnionCodec<Handwritten> {
  override val unionClass: Class<Handwritten> = Handwritten::class.java

  override val schema = UnionSchema(
    name,
    discriminator,
    true,
    UnionVariant("First", firstTag, first),
    UnionVariant("Second", secondTag, second),
  )

  override fun indexOf(value: Handwritten): Int = if (value is Handwritten.First) 0 else 1

  override fun unwrap(value: Handwritten): Any? = when (value) {
    is Handwritten.First -> value.value
    is Handwritten.Second -> value.value
  }

  override fun wrap(index: Int, payload: Any?): Handwritten = when (index) {
    0 -> Handwritten.First(payload)
    1 -> Handwritten.Second(payload)
    else -> noSuchVariant(schema.name, index)
  }
}

@Record
private data class Point(val x: Double, val y: Double, val label: String? = null)

@Record
private data class Circle(val x: Double, val y: Double, val radius: Double)

private fun simple(type: Class<*>) = TypeDescriptor.Simple(type, false)

private val stringMap = TypeDescriptor.Parametrized(
  Map::class.java,
  false,
  arrayOf(simple(String::class.java), simple(Any::class.java)),
)

/** Pure-JVM union tests: what the converter writes, and what the runtime refuses. */
class UnionTypeTest {
  private val dimension = TypeDescriptor.Simple(Dimension::class.java, false).anyConverter

  private val buffer = BinaryBuffer.allocate(4 * 1024)

  private fun roundTrip(value: Any): Any? {
    val out = buffer.duplicateView()
    dimension.writeToBuffer(out, value)
    val view = buffer.duplicateView()
    view.limit = out.position
    return dimension.readFromBuffer(view)
  }

  /** A [HandwrittenCodec]'s schema, as native code would fetch it. */
  private fun schemaOf(codec: HandwrittenCodec): UnionSchemaData =
    UnionType(UnionId(Int.MAX_VALUE), codec).toData()

  @Test
  fun `each variant round-trips through the buffer`() {
    assertEquals(Dimension.Points(2.5), roundTrip(Dimension.Points(2.5)))
    assertEquals(Dimension.Auto, roundTrip(Dimension.Auto))
    assertEquals(Dimension.Percent(0.5), roundTrip(Dimension.Percent(0.5)))
  }

  @Test
  fun `the buffer carries the variant's index, then its value`() {
    val out = buffer.duplicateView()
    dimension.writeToBuffer(out, Dimension.Points(2.5))
    val view = buffer.duplicateView()
    view.limit = out.position
    assertEquals(0, view.getInt())
    assertEquals(2.5, view.getDouble())
    assertEquals(out.position, view.position)

    val literal = buffer.duplicateView()
    dimension.writeToBuffer(literal, Dimension.Auto)
    assertEquals(Int.SIZE_BYTES, literal.position, "an object variant carries no value")
  }

  @Test
  fun `the schema native code fetches lists each variant's kind, tag and type`() {
    val type = UnionRegistry.typeFor(Dimension::class.java)!!
    val data = type.toData()

    assertEquals("Dimension", data.name)
    assertEquals(null, data.discriminator)
    assertTrue(data.bufferSafe)
    assertContentEquals(
      intArrayOf(
        UnionVariantKind.NUMBER.ordinal,
        UnionVariantKind.LITERAL.ordinal,
        UnionVariantKind.OBJECT.ordinal,
      ),
      data.variantKinds,
    )
    assertContentEquals(arrayOf("Points", "auto", "Percent"), data.variantTags)

    val percentCodes = TypeDescriptor.Simple(Dimension.Percent::class.java, false).converter.codes.values
    assertContentEquals(
      intArrayOf(1, CppType.DOUBLE.code, 1, CppType.UNIT.code, percentCodes.size, *percentCodes),
      data.variantTypes,
    )
  }

  @Test
  fun `a discriminated union names its records by their tags`() {
    val data = UnionRegistry.typeFor(Shape::class.java)!!.toData()
    assertEquals("type", data.discriminator)
    assertContentEquals(arrayOf("circle", "Square"), data.variantTags)
  }

  @Test
  fun `a value that is no variant cannot cross`() {
    val type = UnionRegistry.typeFor(Dimension::class.java)!!
    val error = assertFailsWith<IllegalArgumentException> { type.indexOf("not a dimension") }
    assertEquals("java.lang.String is not a variant of Dimension", error.message)
  }

  @Test
  fun `an index past the variants is refused`() {
    val error = assertFailsWith<IllegalArgumentException> {
      dimension.fromJni(UnionValue(7, null))
    }
    assertEquals("Dimension has no variant #7", error.message)
  }

  @Test
  fun `a variant behind one that takes every value it would is refused`() {
    val error = assertFailsWith<IllegalArgumentException> {
      schemaOf(HandwrittenCodec("Numbers", "", TypeDescriptor.Double, TypeDescriptor.Int))
    }
    assertEquals(
      "Numbers cannot cross to JavaScript: Second is never picked - First, declared before it, " +
        "takes every value it would",
      error.message,
    )
  }

  @Test
  fun `the narrower variant declared first is accepted`() {
    schemaOf(HandwrittenCodec("Numbers", "", TypeDescriptor.Int, TypeDescriptor.Double))
    schemaOf(HandwrittenCodec("Links", "", simple(URL::class.java), simple(String::class.java)))

    val error = assertFailsWith<IllegalArgumentException> {
      schemaOf(HandwrittenCodec("Links", "", simple(String::class.java), simple(URL::class.java)))
    }
    assertTrue("Second is never picked" in error.message.orEmpty(), error.message)
  }

  @Test
  fun `a value the picked variant refuses goes to the next one of the same bridge type`() {
    val codec = HandwrittenCodec("Links", "", simple(URL::class.java), simple(String::class.java))
    val type = UnionType(UnionId(Int.MAX_VALUE), codec)

    val address = type.decode(0) { it?.fromJni("https://expo.dev") }
    assertEquals(Handwritten.First(URL("https://expo.dev")), address)
    assertEquals(Handwritten.Second("hello"), type.decode(0) { it?.fromJni("hello") })
  }

  @Test
  fun `a record behind one that needs only some of its fields is refused`() {
    val error = assertFailsWith<IllegalArgumentException> {
      schemaOf(HandwrittenCodec("Objects", "", simple(Point::class.java), simple(Circle::class.java)))
    }
    assertTrue("it needs only [x, y]" in error.message.orEmpty(), error.message)

    schemaOf(HandwrittenCodec("Objects", "", simple(Circle::class.java), simple(Point::class.java)))
  }

  @Test
  fun `a map takes every object`() {
    val error = assertFailsWith<IllegalArgumentException> {
      schemaOf(HandwrittenCodec("Objects", "", stringMap, simple(Circle::class.java)))
    }
    assertTrue("takes every value it would" in error.message.orEmpty(), error.message)
  }

  @Test
  fun `two objects that cross as the same string are refused`() {
    val error = assertFailsWith<IllegalArgumentException> {
      schemaOf(HandwrittenCodec("Literals", "", null, null, firstTag = "auto", secondTag = "auto"))
    }
    assertEquals(
      "Literals cannot cross to JavaScript: Second is never picked - First, declared before it, " +
        "is the same string",
      error.message,
    )
  }

  @Test
  fun `a discriminator needs record variants`() {
    val error = assertFailsWith<IllegalArgumentException> {
      schemaOf(HandwrittenCodec("Tagged", "type", stringMap, null))
    }
    assertTrue("each must be a @Record" in error.message.orEmpty(), error.message)
  }

  @Test
  fun `a record field cannot shadow the discriminator`() {
    val error = assertFailsWith<IllegalArgumentException> {
      schemaOf(
        HandwrittenCodec("Tagged", "percent", TypeDescriptor.Simple(Dimension.Percent::class.java, false), null),
      )
    }
    assertTrue("collide with the union's discriminator" in error.message.orEmpty(), error.message)
  }

  @Test
  fun `a nullable variant value is refused`() {
    val error = assertFailsWith<IllegalArgumentException> {
      schemaOf(HandwrittenCodec("Maybe", "", TypeDescriptor.Simple(String::class.java, true), null))
    }
    assertTrue("cannot be nullable" in error.message.orEmpty(), error.message)
  }
}
