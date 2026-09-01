package io.github.expo.modules.v2.records

// An explicit import beats a same-package declaration, so inside this package the annotation's
// simple name shadows the marker interface. [NeverRegistered] needs the marker.
import io.github.expo.modules.v2.annotations.Record
import io.github.expo.modules.v2.records.Record as RecordMarker
import io.github.expo.modules.v2.converters.RecordConverter
import io.github.expo.modules.v2.types.TypeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Registration happens in the record's `<clinit>`, and generated code names another record with a
 * bare `Other::class.java` — an `ldc` that resolves the class without initializing it (JVMS 5.5).
 * These tests pin the registry's answer to that: a lookup by class forces initialization once.
 *
 * Nothing here may touch [Unvisited] by any route other than a class literal, or the test proves
 * nothing.
 */
class RecordForceInitTest {
  @Test
  fun `a class-literal lookup initializes the record and finds its codec`() {
    val type = RecordRegistry.typeFor(Unvisited::class.java)
    assertNotNull(type, "Unvisited::class.java did not force <clinit>")
    assertEquals("Unvisited", type.schema.name)
  }

  @Test
  fun `a descriptor naming an uninitialized record resolves its converter`() {
    val converter = TypeDescriptor.Simple(AlsoUnvisited::class.java, false).converter
    assertTrue(converter is RecordConverter, converter.toString())
  }

  @Test
  fun `a Record with no codec is answered with null, repeatably`() {
    // The forced init cannot conjure a codec, and the miss must stay a miss: no cached verdict to
    // get stale, and nothing that turns the second answer into a different one.
    assertNull(RecordRegistry.typeFor(NeverRegistered::class.java))
    assertNull(RecordRegistry.typeFor(NeverRegistered::class.java))
  }

  @Test
  fun `a class that is not a Record is answered without a name lookup`() {
    assertNull(RecordRegistry.typeFor(String::class.java))
  }
}

@Record
private data class Unvisited(val x: Int) : io.github.expo.modules.v2.records.Record

@Record
private data class AlsoUnvisited(val y: Int) : io.github.expo.modules.v2.records.Record

/** A record with no codec at all: the registry must answer null, not force a lookup every time. */
private class NeverRegistered : RecordMarker
