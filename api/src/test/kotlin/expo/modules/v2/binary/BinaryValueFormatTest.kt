package expo.modules.v2.binary

import expo.modules.v2.annotations.Record
import expo.modules.v2.records.RecordRegistry
import expo.modules.v2.records.codecFor
import expo.modules.v2.types.AnyType
import expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import expo.modules.v2.types.TypeDescriptor

/**
 * No optional field on purpose: this fixture is round-tripped with Kotlin on both ends, and the
 * presence byte is inbound only. [TypedOpts] covers optionality.
 */
@Record
private data class TypedPoint(val x: Double, val label: String?) : expo.modules.v2.records.Record

/**
 * The optional-field grammar: `count` and `label` carry Kotlin defaults, `x` does not. Inbound
 * only — see the KDoc on [expo.modules.v2.records.writers.BufferRecordWriter].
 */
@Record
private data class TypedOpts(
  val x: Double,
  val count: Int = 5,
  val label: String? = "z",
) : expo.modules.v2.records.Record

private val nullableStringType = TypeDescriptor.Simple(String::class.java, true)

// The internal RecordType handle, for the registry tests that assert on it directly.
private val typedPointType = RecordRegistry.typeFor(codecFor<TypedPoint>())

/**
 * Pure-JVM tests of the schema-directed (untagged) buffer format: encoder and decoder walk the
 * same [AnyType] schema; payloads carry only data. Payload-size assertions pin the grammar.
 */
class BinaryValueFormatTest {
  private val buffer = BinaryBuffer.allocate(64 * 1024)

  /** Encodes into a fresh view, so the shared buffer's own cursor stays at offset 0. */
  private fun encode(value: Any?, schema: AnyType): Int {
    val buf = buffer.duplicateView()
    schema.descriptor.anyConverter.writeToBuffer(buf, value)
    return buf.position
  }

  private fun decode(length: Int, schema: AnyType): Any? {
    val buf = buffer.duplicateView()
    buf.limit = length
    return schema.descriptor.anyConverter.readFromBuffer(buf)
  }

  private fun roundTrip(value: Any?, schema: AnyType, expectedSize: Int? = null): Any? {
    val length = encode(value, schema)
    if (expectedSize != null) {
      assertEquals(expectedSize, length, "payload size (grammar pin)")
    }
    return decode(length, schema)
  }

  @Test
  fun `scalars are pure payload`() {
    assertEquals(true, roundTrip(true, AnyType(TypeDescriptor.Bool), expectedSize = 1))
    assertEquals(42, roundTrip(42, AnyType(TypeDescriptor.Int), expectedSize = 4))
    assertEquals(1L shl 60, roundTrip(1L shl 60, AnyType(TypeDescriptor.Long), expectedSize = 8))
    assertEquals(1.5f, roundTrip(1.5f, AnyType(TypeDescriptor.Float), expectedSize = 4))
    assertEquals(3.25, roundTrip(3.25, AnyType(TypeDescriptor.Double), expectedSize = 8))
    assertEquals(Unit, roundTrip(Unit, AnyType(TypeDescriptor.Simple(Unit::class.java, false)), expectedSize = 0))
  }

  @Test
  fun `strings are prefix plus ASCII bytes or UTF-16 units`() {
    val string = AnyType(TypeDescriptor.Simple(String::class.java, false))
    // ASCII arm: non-negative prefix, one byte per char.
    assertEquals("abc", roundTrip("abc", string, expectedSize = 4 + 3))
    // UTF-16 arm: negative prefix, two bytes per code unit.
    assertEquals("żółć", roundTrip("żółć", string, expectedSize = 4 + 2 * 4))
    assertEquals("😀", roundTrip("😀", string, expectedSize = 4 + 2 * 2)) // one surrogate pair
    assertEquals("", roundTrip("", string, expectedSize = 4))

    // Raw prefix pins: the sign selects the arm. Encoding starts at offset 0 and leaves the
    // caller's position untouched, so the prefix is readable straight off a fresh duplicate.
    assertTrue(encode("abc", string) > 0)
    assertEquals(3, buffer.duplicateView().getInt())
    assertTrue(encode("żółć", string) > 0)
    assertEquals(-4, buffer.duplicateView().getInt())
  }

  @Test
  fun `nullable slots carry one presence byte`() {
    val nullableDouble = AnyType(TypeDescriptor.Simple(Double::class.javaObjectType, true))
    assertEquals(2.5, roundTrip(2.5, nullableDouble, expectedSize = 1 + 8))
    assertNull(roundTrip(null, nullableDouble, expectedSize = 1))
  }

  @Test
  fun `non-nullable slot rejects null at encode`() {
    val error = assertFailsWith<IllegalArgumentException> {
      encode(null, AnyType(TypeDescriptor.Double))
    }
    assertTrue("non-nullable" in error.message!!, error.message)
  }

  @Test
  fun `scalar lists are raw runs`() {
    val doubles = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
      ),
    )
    assertEquals(
      listOf(0.5, 1.5, 2.5),
      roundTrip(listOf(0.5, 1.5, 2.5), doubles, expectedSize = 4 + 3 * 8),
    )
    assertEquals(emptyList<Double>(), roundTrip(emptyList<Double>(), doubles, expectedSize = 4))
  }

  @Test
  fun `nullable scalar list elements carry presence bytes`() {
    val nullableDoubles = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, true)),
      ),
    )
    assertEquals(
      listOf(1.5, null, 2.5),
      roundTrip(listOf(1.5, null, 2.5), nullableDoubles, expectedSize = 4 + (1 + 8) + 1 + (1 + 8)),
    )
  }

  @Test
  fun `nested lists and maps`() {
    val nested = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(
          TypeDescriptor.Parametrized(
            List::class.java,
            false,
            arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
          ),
        ),
      ),
    )
    assertEquals(listOf(listOf(1, 2), listOf(3)), roundTrip(listOf(listOf(1, 2), listOf(3)), nested))

    val mapOfNullable = AnyType(
      TypeDescriptor.Parametrized(
        Map::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(String::class.java, false), TypeDescriptor.Simple(String::class.java, true)),
      ),
    )
    assertEquals(
      mapOf("a" to "x", "b" to null),
      roundTrip(mapOf("a" to "x", "b" to null), mapOfNullable),
    )
  }

  @Test
  fun `null map value in a non-nullable map throws at encode`() {
    val strict = AnyType(
      TypeDescriptor.Parametrized(
        Map::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(String::class.java, false), TypeDescriptor.Simple(String::class.java, false)),
      ),
    )
    assertFailsWith<IllegalArgumentException> {
      encode(mapOf("a" to null), strict)
    }
  }

  @Test
  fun `primitive arrays are count plus raw`() {
    val doubles = AnyType(TypeDescriptor.DoubleArray(false))
    assertContentEquals(
      doubleArrayOf(0.5, 1.5),
      roundTrip(doubleArrayOf(0.5, 1.5), doubles, expectedSize = 4 + 16) as DoubleArray,
    )
    val bytes = AnyType(TypeDescriptor.ByteArray(false))
    assertContentEquals(
      byteArrayOf(1, 2, 127),
      roundTrip(byteArrayOf(1, 2, 127), bytes, expectedSize = 4 + 3) as ByteArray,
    )
  }

  @Test
  fun `typed records have no header and no field tags`() {
    val point = AnyType(TypeDescriptor.Simple(TypedPoint::class.java, false))
    // x: 8 raw bytes; label: 1 presence + 4 len + 5 bytes.
    assertEquals(
      TypedPoint(1.5, "hello"),
      roundTrip(TypedPoint(1.5, "hello"), point, expectedSize = 8 + 1 + 4 + 5),
    )
    // Absent nullable label: presence byte only.
    assertEquals(
      TypedPoint(2.5, null),
      roundTrip(TypedPoint(2.5, null), point, expectedSize = 8 + 1),
    )
  }

  /**
   * Builds an INBOUND record payload — the shape the C++ `BufferEncoder` writes — and decodes it.
   * The Kotlin writer cannot produce this shape: it never emits presence bytes.
   */
  private fun decodeInboundOpts(build: BinaryBuffer.() -> Unit): TypedOpts {
    val buf = buffer.duplicateView()
    buf.build()
    val view = buffer.duplicateView()
    view.limit = buf.position
    return AnyType(TypeDescriptor.Simple(TypedOpts::class.java, false))
      .descriptor
      .anyConverter
      .readFromBuffer(view) as TypedOpts
  }

  @Test
  fun `an optional record field is one inbound presence byte`() {
    val nullableString = nullableStringType

    // All present: x raw, then presence + payload per optional field.
    assertEquals(
      TypedOpts(1.5, 9, "hi"),
      decodeInboundOpts {
        putDouble(1.5)
        putBoolean(true); putInt(9)
        putBoolean(true); nullableString.anyConverter.writeToBuffer(this, "hi")
      },
    )

    // Both absent: one byte each, and the Kotlin defaults apply.
    assertEquals(
      TypedOpts(2.5, 5, "z"),
      decodeInboundOpts {
        putDouble(2.5)
        putBoolean(false)
        putBoolean(false)
      },
    )

    // Present but explicitly null: the presence byte says present, the nullable encoding says
    // null — so the default is NOT applied. Absent, null and a value are three distinct states.
    assertEquals(
      TypedOpts(3.5, 5, null),
      decodeInboundOpts {
        putDouble(3.5)
        putBoolean(false)
        putBoolean(true); nullableString.anyConverter.writeToBuffer(this, null)
      },
    )
  }

  @Test
  fun `outbound record payloads carry no presence byte`() {
    // The Kotlin writer's grammar is unchanged by optionality: 8 raw bytes for x, 4 for count,
    // 1 + 4 + 2 for the nullable string. Its output is therefore NOT readable by the codec's own
    // decode — that asymmetry is the point of an inbound-only flag.
    val opts = AnyType(TypeDescriptor.Simple(TypedOpts::class.java, false))
    assertEquals(8 + 4 + 1 + 4 + 2, encode(TypedOpts(1.5, 9, "hi"), opts))
  }

  @Test
  fun `lists of records are back-to-back bodies`() {
    val points = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(TypedPoint::class.java, false)),
      ),
    )
    val value = listOf(TypedPoint(1.0, null), TypedPoint(2.0, null))
    // count + 2 × (8 + 1)
    assertEquals(value, roundTrip(value, points, expectedSize = 4 + 2 * 9))
  }

  @Test
  fun `DYNAMIC schemas embed the tagged format`() {
    val dynamic = AnyType(TypeDescriptor.Simple(Any::class.java, false))
    assertEquals(
      mapOf("a" to 1.0, "b" to listOf(true, null)),
      roundTrip(mapOf("a" to 1.0, "b" to listOf(true, null)), dynamic),
    )
  }

  @Test
  fun `a DYNAMIC slot declares nullability like every other kind`() {
    // Nested nulls always belong to the tagged universe (asserted above); it is the slot itself
    // that has to declare one.
    val error = assertFailsWith<IllegalArgumentException> {
      roundTrip(null, AnyType(TypeDescriptor.Simple(Any::class.java, false)))
    }
    assertTrue("non-nullable" in error.message!!, error.message)

    // Declared nullable, it carries the same presence byte as any other nullable kind: absent is
    // that byte alone, present is the byte plus the tagged body.
    val nullableDynamic = AnyType(TypeDescriptor.Simple(Any::class.java, true))
    assertNull(roundTrip(null, nullableDynamic, expectedSize = 1))
    assertEquals(2.5, roundTrip(2.5, nullableDynamic, expectedSize = 1 + 1 + 8))
  }

  @Test
  fun `equal declarations emit one code stream`() {
    val a = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
      ),
    )
    val b = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
      ),
    )
    val c = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
      ),
    )
    assertTrue(a.codes.contentEquals(b.codes))
    assertTrue(!a.codes.contentEquals(c.codes))
  }


  @Test
  fun `a mismatched runtime class fails fast on the slot's own cast`() {
    // The converter for the declared kind states its own Kotlin type, so the cast is the one its
    // signature forces; nothing wraps it, and a wrong runtime class surfaces as that cast. A
    // numeric slot casts through Number, the class it unboxes from.
    val numeric = assertFailsWith<ClassCastException> {
      encode("not a number", AnyType(TypeDescriptor.Double))
    }
    assertTrue("String" in numeric.message!!, numeric.message)
    assertTrue("Number" in numeric.message!!, numeric.message)

    val string = assertFailsWith<ClassCastException> {
      encode(42, AnyType(TypeDescriptor.Simple(String::class.java, false)))
    }
    assertTrue("String" in string.message!!, string.message)
  }
}
