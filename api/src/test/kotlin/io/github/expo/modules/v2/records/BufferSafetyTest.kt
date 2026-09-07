package io.github.expo.modules.v2.records

import io.github.expo.modules.v2.Record
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.jsi.JavaScriptValue

@Record
private data class SafePlain(val n: Int, val label: String?) : io.github.expo.modules.v2.records.Record

/** [SafePlain]'s field types exactly, with both fields declared optional. */
@Record
private data class OptionalFields(val n: Int = 1, val label: String? = "x") : io.github.expo.modules.v2.records.Record

@Record(bufferSafe = false)
private class HandleHolder(val target: JavaScriptObject) : io.github.expo.modules.v2.records.Record

@Record(bufferSafe = false)
private class DynamicHolder(val meta: Map<String, Any>) : io.github.expo.modules.v2.records.Record

@Record(bufferSafe = false)
private class HolderWrapper(val name: String, val inner: List<HandleHolder>) : io.github.expo.modules.v2.records.Record

// A handle-free cycle: both members must stay buffer-safe.
@Record
private data class LoopA(val n: Int, val b: LoopB?) : io.github.expo.modules.v2.records.Record

@Record
private data class LoopB(val n: Int, val a: LoopA?) : io.github.expo.modules.v2.records.Record

// A cycle where one member holds a handle: the taint must reach every cycle member.
@Record(bufferSafe = false)
private class TaintedX(val y: TaintedY?) : io.github.expo.modules.v2.records.Record

@Record(bufferSafe = false)
private class TaintedY(val x: TaintedX?, val handle: JavaScriptValue) : io.github.expo.modules.v2.records.Record

/**
 * Pins the routing predicate: a type is buffer-safe iff nothing in its tree — through containers
 * and reachable record schemas — is ANY or a JSI handle. Non-buffer-safe types cross JNI as
 * plain object slots instead of trampoline payloads.
 *
 * The flag is an `@Record(bufferSafe = ...)` argument rather than something the plugin infers:
 * taint is transitive, and a nested record from another module does not expose its own flag to the
 * compiler. `RecordCheckers` reports a record that holds an unsafe field and forgot to say so.
 */
class BufferSafetyTest {
  private fun schemaOf(codec: RecordCodec<*>) = RecordRegistry.typeFor(codec)

  @Test
  fun `a plain record is buffer-safe`() {
    assertTrue(schemaOf(codecFor<SafePlain>()).schema.bufferSafe)
  }

  @Test
  fun `a record with a JSI handle field is not buffer-safe`() {
    val type = schemaOf(codecFor<HandleHolder>())
    assertFalse(type.schema.bufferSafe)
    assertFalse(RecordRegistry.fetchSchema(type.schemaId.value).bufferSafe)
  }

  @Test
  fun `a record with a dynamic field is not buffer-safe`() {
    assertFalse(schemaOf(codecFor<DynamicHolder>()).schema.bufferSafe)
  }

  @Test
  fun `taint is transitive through nested record references`() {
    assertFalse(schemaOf(codecFor<HolderWrapper>()).schema.bufferSafe)
  }

  @Test
  fun `a handle-free codec cycle stays buffer-safe`() {
    assertTrue(schemaOf(codecFor<LoopA>()).schema.bufferSafe)
    assertTrue(schemaOf(codecFor<LoopB>()).schema.bufferSafe)
  }

  @Test
  fun `optionality stays out of buffer safety and the type-code stream`() {
    // A field's optionality lives on the field, not on its type: it travels to native in its own
    // boolean array, so it cannot reach the routing predicate or the type codes.
    val plain = RecordRegistry.fetchSchema(schemaOf(codecFor<SafePlain>()).schemaId.value)
    val optional = RecordRegistry.fetchSchema(schemaOf(codecFor<OptionalFields>()).schemaId.value)

    assertTrue(optional.bufferSafe)
    assertContentEquals(plain.fieldTypes, optional.fieldTypes)
    assertContentEquals(booleanArrayOf(false, false), plain.fieldOptional)
    assertContentEquals(booleanArrayOf(true, true), optional.fieldOptional)
  }

  @Test
  fun `a handle anywhere in a codec cycle taints every member`() {
    assertFalse(schemaOf(codecFor<TaintedX>()).schema.bufferSafe)
    assertFalse(schemaOf(codecFor<TaintedY>()).schema.bufferSafe)
  }
}
