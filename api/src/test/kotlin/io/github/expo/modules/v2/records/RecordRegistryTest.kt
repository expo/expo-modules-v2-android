package io.github.expo.modules.v2.records

// An explicit import beats a same-package declaration, so inside this package the annotation's
// simple name shadows the marker interface. The two hand-written codecs below need the marker.
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.Record as RecordMarker
import io.github.expo.modules.v2.records.readers.BufferRecordReader
import io.github.expo.modules.v2.records.readers.RecordReader
import io.github.expo.modules.v2.records.writers.RecordWriter
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.anyConverter
import kotlin.test.assertContentEquals
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.kolibri.binary.BinaryTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import io.github.expo.modules.v2.types.TypeDescriptor

/**
 * `label` carries no Kotlin default on purpose. A default makes the field optional, and the
 * presence byte an optional field is read through is inbound only — `BufferRecordWriter` never
 * writes one — so a record with a default cannot round-trip Kotlin -> buffer -> Kotlin the way
 * these tests do. Optional fields are covered by BinaryValueFormatTest, RecordMapCodecTest and
 * TrampolineTest, which each drive one direction.
 */
@Record
private data class Point(val x: Double, val y: Double, val label: String?) : io.github.expo.modules.v2.records.Record

@Record
private data class Segment(val start: Point, val end: Point) : io.github.expo.modules.v2.records.Record

@Record(bufferSafe = false)
private data class Payload(
  val name: String,
  val samples: DoubleArray,
  val meta: Map<String, Any?>,
) : io.github.expo.modules.v2.records.Record

/**
 * The generated codec registers itself from the record's static init, so constructing an instance
 * guarantees registration and the record can cross dynamically (inside an `ANY` payload) with no
 * explicit register call anywhere.
 */
@Record
private data class Badge(val id: Int, val title: String) : io.github.expo.modules.v2.records.Record

// Mutually recursive records: each names the other with a bare class literal, which resolves the
// class without initializing it. RecordRegistry.typeFor forces that init on the miss.
@Record
private data class Ping(val n: Int, val pong: Pong?) : io.github.expo.modules.v2.records.Record

@Record
private data class Pong(val n: Int, val ping: Ping?) : io.github.expo.modules.v2.records.Record

/** Pure-JVM record codec tests: the native push stays queued, so no native code is touched. */
class RecordRegistryTest {
  private val pointCodec = codecFor<Point>()
  private val pointType = RecordRegistry.typeFor(pointCodec)
  private val buffer = BinaryBuffer.allocate(64 * 1024)

  // Records reach the buffer by runtime class through the tagged (ANY) format.
  private val dynamic = AnyType(TypeDescriptor.Simple(Any::class.java, false))

  /** Encodes into a fresh view, so the shared buffer's own cursor stays at offset 0. */
  private fun encodeDynamic(value: Any?): Int {
    val buf = buffer.duplicateView()
    dynamic.descriptor.anyConverter.writeToBuffer(buf, value)
    return buf.position
  }

  private fun roundTrip(value: Any?): Any? {
    val length = encodeDynamic(value)
    val view = buffer.duplicateView()
    view.limit = length
    return dynamic.descriptor.anyConverter.readFromBuffer(view)
  }

  @Test
  fun `round-trips a record`() {
    assertEquals(Point(1.5, -2.5, "origin"), roundTrip(Point(1.5, -2.5, "origin")))
  }

  @Test
  fun `round-trips a null field`() {
    assertEquals(Point(1.0, 2.0, null), roundTrip(Point(1.0, 2.0, null)))
  }

  @Test
  fun `round-trips nested records`() {
    val segment = Segment(Point(0.0, 0.0, null), Point(3.0, 4.0, "end"))
    assertEquals(segment, roundTrip(segment))
  }

  @Test
  fun `round-trips records inside lists and maps`() {
    val points = listOf(Point(1.0, 2.0, null), Point(3.0, 4.0, "b"))
    assertEquals(points, roundTrip(points))
    val map = mapOf("a" to Point(1.0, 1.0, null), "b" to null)
    assertEquals(map, roundTrip(map))
  }

  @Test
  fun `round-trips record fields holding arrays and maps via a declared descriptor`() {
    val payload = Payload("sensor", doubleArrayOf(0.5, 1.5), mapOf("ok" to true, "n" to null))
    val result = roundTrip(payload) as Payload
    assertEquals(payload.name, result.name)
    assertEquals(payload.samples.toList(), result.samples.toList())
    assertEquals(payload.meta, result.meta)
  }

  // A Kotlin default is a real feature of the field: the generated schema marks it
  // `isOptional = true` and the generated `decode` gates the read on `RecordReader.readIsPresent()`.
  // See [Point]'s doc for why none of the records here declare one.

  @Test
  fun `a buffer reader replays the record positionally`() {
    val length = encodeDynamic(Point(1.0, 2.0, "origin"))
    val view = buffer.duplicateView()
    view.limit = length
    view.getTag() // EXTERNAL_SCHEMA (the ANY slot's tagged body)
    view.getInt() // schemaId
    val reader = BufferRecordReader(view)
    assertEquals(1.0, reader.readDouble())
    assertEquals(2.0, reader.readDouble())
    val labelType = AnyType(TypeDescriptor.Simple(String::class.java, true))
    assertEquals("origin", reader.read(labelType.descriptor))
  }

  @Test
  fun `decoding an unknown schemaId fails descriptively`() {
    val raw = BinaryBuffer.allocate(16)
    raw.putTag(BinaryTag.EXTERNAL_SCHEMA)
    raw.putInt(999_999)
    val view = raw.duplicateView()
    view.limit = raw.position
    val error = assertFailsWith<IllegalStateException> {
      dynamic.descriptor.anyConverter.readFromBuffer(view)
    }
    assertTrue("999999" in error.message!!, error.message)
    assertTrue("RecordRegistry.register" in error.message!!, error.message)
  }

  @Test
  fun `encoding an unregistered class fails descriptively`() {
    data class Stranger(val x: Int)
    val error = assertFailsWith<IllegalArgumentException> {
      encodeDynamic(Stranger(1))
    }
    assertTrue("RecordRegistry.register" in error.message!!, error.message)
  }

  @Test
  fun `constructing a record registers its generated codec`() {
    // No register call in any test: constructing Badge runs its class init, which builds the
    // generated codec and registers it — so the dynamic encode's class lookup hits.
    assertEquals(Badge(7, "gold"), roundTrip(Badge(7, "gold")))
    assertEquals(listOf(Badge(1, "a"), Badge(2, "b")), roundTrip(listOf(Badge(1, "a"), Badge(2, "b"))))
  }

  @Test
  fun `registration is idempotent and rejects conflicting codecs`() {
    assertSame(pointType, RecordRegistry.typeFor(pointCodec))
    val conflicting = object : RecordCodec<Point> {
      override val recordClass = Point::class.java
      override val schema = pointCodec.schema
      override fun encode(value: Point, writer: RecordWriter) = pointCodec.encode(value, writer)
      override fun decode(reader: RecordReader) = pointCodec.decode(reader)
    }
    assertFailsWith<IllegalArgumentException> { RecordRegistry.register(conflicting) }
  }

  @Test
  fun `record schemas compose with containers`() {
    assertContentEquals(
      intArrayOf(CppType.RECORD.code, pointType.schemaId.value),
      AnyType(TypeDescriptor.Simple(Point::class.java, false)).codes.values,
    )
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.RECORD.code, pointType.schemaId.value),
      AnyType(
        TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(Point::class.java, false))),
      ).codes.values,
    )
  }

  @Test
  fun `mutually recursive codecs register through each other`() {
    val ping = Ping(1, Pong(2, Ping(3, null)))
    assertEquals(ping, roundTrip(ping))
  }

  @Test
  fun `reading codes on an unregistered codec names the missing init block`() {
    // `codes` is a lookup, not a registration: a codec with no `init { register(this) }` is
    // reported here rather than silently interning itself.
    data class Unregistered(val x: Int) : RecordMarker

    val orphan = object : RecordCodec<Unregistered> {
      override val recordClass = Unregistered::class.java
      override val schema = RecordSchema("Unregistered", true, RecordField("x", TypeDescriptor.Int))
      override fun encode(value: Unregistered, writer: RecordWriter) = writer.write(value.x)
      override fun decode(reader: RecordReader) = Unregistered(reader.readInt())
    }

    val error = assertFailsWith<IllegalStateException> { RecordRegistry.typeFor(orphan) }
    assertTrue("is not registered" in error.message!!, error.message)
    assertTrue("RecordRegistry.register(this)" in error.message!!, error.message)
  }

  @Test
  fun `registering before recordClass is initialized names the ordering rule`() {
    // The interface form cannot enforce declaration order, so an `init` block placed above
    // `recordClass` sees null. Report that rather than a bare NullPointerException.
    data class TooEarly(val x: Int) : RecordMarker

    val error = assertFailsWith<IllegalArgumentException> {
      object : RecordCodec<TooEarly> {
        init {
          RecordRegistry.register(this)
        }

        override val recordClass = TooEarly::class.java
        override val schema = RecordSchema("TooEarly", true, RecordField("x", TypeDescriptor.Int))
        override fun encode(value: TooEarly, writer: RecordWriter) = writer.write(value.x)
        override fun decode(reader: RecordReader) = TooEarly(reader.readInt())
      }
    }
    assertTrue("recordClass" in error.message!!, error.message)
  }
}
