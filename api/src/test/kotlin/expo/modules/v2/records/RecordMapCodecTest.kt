package expo.modules.v2.records

import expo.modules.v2.annotations.Record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import expo.modules.v2.jsi.JavaScriptObject

@Record
private data class MapPoint(val x: Double, val y: Double, val label: String? = null) : expo.modules.v2.records.Record

/**
 * Optional fields whose defaults are NOT null, so absent, `null` and a value are three visibly
 * distinct outcomes. `tags` is mandatory, which pins the field cursor: an absent optional field
 * must not shift the fields that follow it.
 */
@Record
private data class Opts(
  val tags: List<String>,
  val count: Int = 5,
  val label: String? = "x",
  val trailing: Int = 7,
) : expo.modules.v2.records.Record

@Record(bufferSafe = false)
private data class Envelope(
  val tag: String,
  val payload: Any?,
  val point: MapPoint,
  val extras: List<MapPoint>,
) : expo.modules.v2.records.Record

/** [Envelope]'s dynamic field the other way round: `Any?` declared as a nullable `Any`. */
@Record(bufferSafe = false)
private data class Wrapper(val value: Any?) : expo.modules.v2.records.Record

/** A handle field: it can only cross as a map, and it passes through both ways untouched. */
@Record(bufferSafe = false)
private class MapOnly(val h: JavaScriptObject) : expo.modules.v2.records.Record

/** The schema name is an annotation argument, not the class's simple name. */
@Record(name = "Half")
private data class MapPoint2(val x: Double, val y: Double) : expo.modules.v2.records.Record

/**
 * One field of every shape the generated `encode` dispatches differently: a raw primitive, a boxed
 * nullable primitive, a descriptor-driven leaf, a container and a nested record. Their order is
 * what [`toMap` writes fields in declaration order`] pins.
 */
@Record
private data class Mixed(
  val n: Int,
  val maybe: Int?,
  val name: String,
  val tags: List<String>,
  val point: MapPoint,
  val flag: Boolean,
) : expo.modules.v2.records.Record

/**
 * Pure-JVM coverage for the Map crossing shape: a record that cannot ride the binary buffer
 * crosses as a `Map<String, Any?>` built and consumed by the default [RecordCodec.toMap] /
 * [RecordCodec.fromMap], which drive the codec's one `encode`/`decode` pair through the
 * schema-directed map writer/reader. Real JSI-handle fields are covered end-to-end in
 * HermesRuntimeTest — a handle cannot be constructed on a bare JVM.
 */
class RecordMapCodecTest {
  @Test
  fun `round-trips scalar and nullable fields through the codec's map functions`() {
    val map = codecFor<MapPoint>().toMap(MapPoint(1.5, -2.5, "origin"))
    assertEquals(mapOf("x" to 1.5, "y" to -2.5, "label" to "origin"), map)
    assertEquals(MapPoint(1.5, -2.5, "origin"), codecFor<MapPoint>().fromMap(map))
    assertEquals(MapPoint(1.0, 2.0, label = null), codecFor<MapPoint>().fromMap(codecFor<MapPoint>().toMap(MapPoint(1.0, 2.0))))
  }

  @Test
  fun `nested records become nested maps and rebuild`() {
    val envelope = Envelope(
      "t",
      mapOf("k" to 1),
      MapPoint(0.0, 1.0),
      listOf(MapPoint(2.0, 3.0, "a"), MapPoint(4.0, 5.0)),
    )
    val map = codecFor<Envelope>().toMap(envelope)
    @Suppress("UNCHECKED_CAST")
    val point = map["point"] as Map<String, Any?>
    assertEquals(0.0, point["x"])
    val extras = map["extras"] as List<*>
    assertTrue(extras.all { it is Map<*, *> }, "list elements must decompose to maps")
    assertEquals(envelope, codecFor<Envelope>().fromMap(map))
  }

  @Test
  fun `ANY fields pass through unchanged`() {
    val opaque = Any()
    val envelope = Envelope("t", opaque, MapPoint(0.0, 0.0), emptyList())
    val map = codecFor<Envelope>().toMap(envelope)
    assertSame(opaque, map["payload"])
    assertSame(opaque, codecFor<Envelope>().fromMap(map).payload)
  }

  @Test
  fun `a dynamic field carries null through the map shape untouched`() {
    // ANY is a passthrough on the map path — the writer and reader state the declared type and
    // nothing else, so a null crosses as the value it is, in either nullability.
    assertNull(codecFor<Wrapper>().fromMap(codecFor<Wrapper>().toMap(Wrapper(null))).value)
    assertEquals("x", codecFor<Wrapper>().fromMap(codecFor<Wrapper>().toMap(Wrapper("x"))).value)
    assertNull(codecFor<Envelope>().toMap(Envelope("t", null, MapPoint(0.0, 0.0), emptyList()))["payload"])
  }

  @Test
  fun `a map that does not match the schema fails on the read's cast`() {
    // The map reader states the field's declared type and nothing else: native builds the map
    // schema-directed, so a missing key or a foreign value is a native bug, not a codec contract.
    assertFailsWith<NullPointerException> {
      codecFor<MapPoint>().fromMap(mapOf("x" to 1.0, "label" to null))
    }
    assertFailsWith<ClassCastException> {
      codecFor<MapPoint>().fromMap(mapOf("x" to 1.0, "y" to "oops"))
    }
  }

  @Test
  fun `a missing key on an optional field yields the Kotlin default`() {
    // Native leaves an absent optional field out of the map entirely.
    assertEquals(
      Opts(listOf("a")),
      codecFor<Opts>().fromMap(mapOf("tags" to listOf("a"))),
    )
    // A present key wins over the default, and an absent one in the middle must not shift the
    // field that follows it.
    assertEquals(
      Opts(listOf("a"), count = 9, label = "x", trailing = 3),
      codecFor<Opts>().fromMap(mapOf("tags" to listOf("a"), "count" to 9, "trailing" to 3)),
    )
  }

  @Test
  fun `an explicit null on an optional nullable field is not the default`() {
    // Absent, null and a value are three distinct states: the key is there, so null crosses.
    assertEquals(
      Opts(listOf("a"), label = null),
      codecFor<Opts>().fromMap(mapOf("tags" to listOf("a"), "label" to null)),
    )
  }

  @Test
  fun `the map path stays symmetric for optional fields`() {
    // MapRecordWriter always writes every key, so its own reader always sees them present.
    val opts = Opts(listOf("a", "b"), count = 1, label = null, trailing = 2)
    assertEquals(opts, codecFor<Opts>().fromMap(codecFor<Opts>().toMap(opts)))
    assertEquals(
      setOf("tags", "count", "label", "trailing"),
      codecFor<Opts>().toMap(opts).keys,
    )
  }

  @Test
  fun `handle fields cross the map path untouched`() {
    // A JSI handle cannot be constructed on a bare JVM, so what is checkable here is the property
    // that makes the map path carry one unchanged: its converter is a passthrough, so the writer
    // and reader hand the value over without conversion. The crossing itself is in HermesRuntimeTest.
    val field = codecFor<MapOnly>().schema.fields.single()
    assertEquals("h", field.name)
    assertTrue(field.type.converter.isPassthrough, "a handle field must be a passthrough")
  }

  @Test
  fun `toMap writes fields in declaration order`() {
    // MapRecordWriter and MapRecordReader share one moving cursor over schema.fields. If `encode`
    // ever emitted in a different order from `schema`, the map would silently mislabel fields —
    // every value would still be present, just under the wrong key. Only an ordered assertion on a
    // record whose fields have distinguishable values can catch that.
    val mixed = Mixed(1, null, "n", listOf("t"), MapPoint(1.0, 2.0, "p"), true)
    val map = codecFor<Mixed>().toMap(mixed)

    assertEquals(
      listOf("n", "maybe", "name", "tags", "point", "flag"),
      map.keys.toList(),
    )
    assertEquals(
      listOf("n", "maybe", "name", "tags", "point", "flag"),
      codecFor<Mixed>().schema.fields.map { it.name },
    )
    assertEquals(1, map["n"])
    assertEquals(null, map["maybe"])
    assertEquals("n", map["name"])
    assertEquals(listOf("t"), map["tags"])
    assertEquals(mapOf("x" to 1.0, "y" to 2.0, "label" to "p"), map["point"])
    assertEquals(true, map["flag"])

    assertEquals(mixed, codecFor<Mixed>().fromMap(map))
  }

  @Test
  fun `a record can rename its schema`() {
    assertEquals("Half", codecFor<MapPoint2>().schema.name)
  }

  @Test
  fun `dynamicRecordToMap delegates to the codec and misses unregistered classes`() {
    assertEquals(
      mapOf("x" to 1.0, "y" to 2.0, "label" to null),
      RecordRegistry.dynamicRecordToMap(MapPoint(1.0, 2.0)),
    )
    class NotARecord
    assertNull(RecordRegistry.dynamicRecordToMap(NotARecord()))
  }
}
