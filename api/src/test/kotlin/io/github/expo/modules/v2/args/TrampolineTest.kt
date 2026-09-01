package io.github.expo.modules.v2.args

import io.github.expo.modules.v2.records.writers.BufferRecordWriter
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.annotations.Record
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.records.codecFor
import io.github.expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer
import java.io.File
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.expo.modules.v2.types.TypeDescriptor
import java.nio.BufferOverflowException

@Record
private data class Item(val x: Int, val b: String) : io.github.expo.modules.v2.records.Record

/** An optional field, so an inbound payload gates `text` with a presence byte. */
@Record
private data class Note(val v: Int, val text: String? = null) : io.github.expo.modules.v2.records.Record

private val nullableStringType = TypeDescriptor.Simple(String::class.java, true)

// The internal RecordType handle, for driving the internal `*To`/`readTypedRecord` seams.
private val itemType = RecordRegistry.typeFor(codecFor<Item>())

/**
 * Pure-JVM half of the trampoline contract: the argument payload the bridge writes (concatenated
 * schema-directed record payloads at offset 0) reads back positionally, and the result payload a
 * trampoline writes decodes with the schema the bridge expects. Uses [BinaryBuffer.allocate] and
 * the `*To`/`argumentsOf` seams, so no natives are involved.
 */
class TrampolineTest {
  private val buffer = BinaryBuffer.allocate(4 * 1024)

  @Test
  fun `reads concatenated record arguments positionally`() {
    val writer = buffer.duplicateView()
    val writer1 = BufferRecordWriter(writer)
    itemType.anyCodec.encode(Item(7, "seven"), writer1)
    // Note has an optional field, so its inbound body carries a presence byte the Kotlin writer
    // never emits — see BufferRecordWriter's KDoc. Hand-build what native would have written.
    writer.putInt(1)
    writer.putBoolean(true)
    nullableStringType.anyConverter.writeToBuffer(writer, "note")

    val buf = buffer.duplicateView()
    buf.limit = writer.position
    val args = BufferTrampolineArguments(buf)
    assertEquals(Item(7, "seven"), args.next(TypeDescriptor.Simple(Item::class.java, false)))
    assertEquals(Note(1, "note"), args.next(TypeDescriptor.Simple(Note::class.java, false)))
    args.finish()
  }

  @Test
  fun `an absent optional field in a record argument takes the Kotlin default`() {
    val writer = buffer.duplicateView()
    writer.putInt(4)
    writer.putBoolean(false) // `text` missing or undefined in JS
    // The next argument starts right after the presence byte: an absent field costs one byte.
    val writer1 = BufferRecordWriter(writer)
    itemType.anyCodec.encode(Item(7, "seven"), writer1)

    val buf = buffer.duplicateView()
    buf.limit = writer.position
    val args = BufferTrampolineArguments(buf)
    assertEquals(Note(4, null), args.next(TypeDescriptor.Simple(Note::class.java, false)))
    assertEquals(Item(7, "seven"), args.next(TypeDescriptor.Simple(Item::class.java, false)))
    args.finish()
  }

  @Test
  fun `a nullable record argument is gated by a presence byte`() {
    val writer = buffer.duplicateView()
    writer.putBoolean(false)
    writer.putBoolean(true)
    val writer1 = BufferRecordWriter(writer)
    itemType.anyCodec.encode(Item(3, "x"), writer1)

    val buf = buffer.duplicateView()
    buf.limit = writer.position
    val args = BufferTrampolineArguments(buf)
    assertNull(args.next<Item?>(TypeDescriptor.Simple(Item::class.java, true)))
    assertEquals(Item(3, "x"), args.next(TypeDescriptor.Simple(Item::class.java, true)))
    args.finish()
  }

  @Test
  fun `writeResult falls back to the converted object when the shared buffer overflows`() {
    val fileList = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(File::class.java, false)),
      ),
    )
    val files = List(30_000) { File("/tmp/f$it") }

    assertEquals(
      Trampoline.OVERFLOW_RESULT,
      Trampoline.writeResultTo(BinaryBuffer.allocate(256 * 1024), files, fileList.descriptor),
    )
    assertEquals(files.map(File::getAbsolutePath), Trampoline.takeOverflowResult())
  }

  @Test
  fun `reads typed list and map arguments positionally`() {
    val doubleList = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
      ),
    )
    val intMap = AnyType(
      TypeDescriptor.Parametrized(
        Map::class.java,
        false,
        arrayOf(
          TypeDescriptor.Simple(String::class.java, false),
          TypeDescriptor.Simple(Int::class.javaObjectType, false),
        ),
      ),
    )
    val writer = buffer.duplicateView()
    doubleList.descriptor.anyConverter.writeToBuffer(writer, listOf(0.5, 1.5))
    intMap.descriptor.anyConverter.writeToBuffer(writer, mapOf("a" to 1))
    val writer1 = BufferRecordWriter(writer)
    itemType.anyCodec.encode(Item(7, "seven"), writer1)

    val buf = buffer.duplicateView()
    buf.limit = writer.position
    val args = BufferTrampolineArguments(buf)
    assertEquals(listOf(0.5, 1.5), args.next(doubleList.descriptor))
    assertEquals(mapOf("a" to 1), args.next(intMap.descriptor))
    assertEquals(Item(7, "seven"), args.next(TypeDescriptor.Simple(Item::class.java, false)))
    args.finish()
  }

  @Test
  fun `reads buffered leaf arguments positionally`() {
    val string = AnyType(TypeDescriptor.Simple(String::class.java, false))
    val nullableInt = AnyType(TypeDescriptor.Simple(Int::class.javaObjectType, true))
    val writer = buffer.duplicateView()
    string.descriptor.anyConverter.writeToBuffer(writer, "héj ⚡")
    nullableInt.descriptor.anyConverter.writeToBuffer(writer, 7)
    nullableInt.descriptor.anyConverter.writeToBuffer(writer, null)
    TypeDescriptor.IntArray(false).anyConverter.writeToBuffer(writer, intArrayOf(1, 2, 3))
    TypeDescriptor.ByteArray(false).anyConverter.writeToBuffer(writer, byteArrayOf(1, -2))
    // A leaf-bridge converter reads its buffered bridge value like any other payload parameter.
    string.descriptor.anyConverter.writeToBuffer(writer, "https://expo.dev/x")

    val buf = buffer.duplicateView()
    buf.limit = writer.position
    val args = BufferTrampolineArguments(buf)
    assertEquals("héj ⚡", args.nextString())
    assertEquals(7, args.nextIntOrNull())
    assertNull(args.nextIntOrNull())
    assertContentEquals(intArrayOf(1, 2, 3), args.nextIntArray())
    assertContentEquals(byteArrayOf(1, -2), args.nextByteArray())
    assertEquals(
      "https://expo.dev/x",
      args.next<URL>(TypeDescriptor.Simple(URL::class.java, false)).toString(),
    )
    args.finish()
  }

  @Test
  fun `writes buffered leaf results the bridge schema decodes`() {
    val stringSchema = AnyType(TypeDescriptor.Simple(String::class.java, false))
    val buf = buffer.duplicateView()
    stringSchema.descriptor.anyConverter.writeToBuffer(buf, "ok ✓")
    val length = buf.position
    val reader = buffer.duplicateView()
    reader.limit = length
    assertEquals("ok ✓", stringSchema.descriptor.anyConverter.readFromBuffer(reader))

    val arraySchema = AnyType(TypeDescriptor.DoubleArray(false))
    val buf1 = buffer.duplicateView()
    arraySchema.descriptor.anyConverter.writeToBuffer(buf1, doubleArrayOf(0.5, 1.5))
    val arrayLength = buf1.position
    val arrayReader = buffer.duplicateView()
    arrayReader.limit = arrayLength
    assertContentEquals(
      doubleArrayOf(0.5, 1.5),
      arraySchema.descriptor.anyConverter.readFromBuffer(arrayReader) as DoubleArray,
    )
  }

  @Test
  fun `reads overflow object slots consecutively in payload order and releases them`() {
    val itemList = AnyType(
      TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(Item::class.java, false))),
    )
    val nullableInts = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        true,
        arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
      ),
    )
    // Native fills the slots consecutively in payload order — not at the declared argument
    // index — and the one cursor reads them back the same way.
    val slots = Trampoline.prepareOverflowArguments()
    slots[0] = listOf(mapOf("x" to 1, "b" to "a"), mapOf("x" to 2, "b" to "b"))
    slots[1] = null
    slots[2] = mapOf("x" to 3, "b" to "c")

    val args = Trampoline.arguments(Trampoline.OVERFLOW_ARGUMENTS)
    assertEquals(listOf(Item(1, "a"), Item(2, "b")), args.next(itemList.descriptor))
    assertNull(slots[0], "a consumed slot must release its reference immediately")
    assertNull(args.next<List<Int>?>(nullableInts.descriptor))
    assertEquals(Item(3, "c"), args.next(TypeDescriptor.Simple(Item::class.java, false)))
    args.finish()
    assertTrue(slots.all { it == null })
  }

  @Test
  fun `finish releases overflow slots a failed call left behind`() {
    val slots = Trampoline.prepareOverflowArguments()
    slots[0] = listOf(1)
    slots[1] = null // a non-nullable declaration must not accept it
    slots[2] = listOf(2)

    val intList = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
      ),
    )
    val args = Trampoline.arguments(Trampoline.OVERFLOW_ARGUMENTS)
    assertEquals(listOf(1), args.next(intList.descriptor))
    // The second read throws, so slot 2 is never reached: finish releases it from a `finally`.
    assertFailsWith<IllegalArgumentException> { args.next<List<Int>>(intList.descriptor) }
    args.finish()
    assertTrue(slots.all { it == null })

    // The state is reusable without native-side cleanup after the failed call.
    Trampoline.prepareOverflowArguments()
    Trampoline.arguments(Trampoline.OVERFLOW_ARGUMENTS).finish()
  }

  @Test
  fun `writes a typed result the bridge schema decodes`() {
    val itemList = AnyType(
      TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(Item::class.java, false))),
    )
    val items = listOf(Item(1, "a"), Item(2, "b"))
    val buf = buffer.duplicateView()
    itemList.descriptor.anyConverter.writeToBuffer(buf, items)
    val length = buf.position

    val view = buffer.duplicateView()
    view.limit = length
    assertEquals(items, itemList.descriptor.anyConverter.readFromBuffer(view))
    assertEquals(length, view.position, "trailing bytes in the result payload")
  }

  @Test
  fun `a nullable typed result is gated by a presence byte`() {
    val nullableList = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        true,
        arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
      ),
    )
    val buf = buffer.duplicateView()
    nullableList.descriptor.anyConverter.writeToBuffer(buf, null)
    val nullLength = buf.position
    assertEquals(1, nullLength)

    val buf1 = buffer.duplicateView()
    nullableList.descriptor.anyConverter.writeToBuffer(buf1, listOf(1, 2))
    val length = buf1.position
    val view = buffer.duplicateView()
    view.limit = length
    assertEquals(listOf(1, 2), nullableList.descriptor.anyConverter.readFromBuffer(view))
  }

  @Test
  fun `a typed result that does not fit the buffer fails fast`() {
    val tiny = BinaryBuffer.allocate(4)
    assertFailsWith<BufferOverflowException> {
      val schema = AnyType(
        TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(String::class.java, false))),
      )
      schema.descriptor.anyConverter.writeToBuffer(tiny, listOf("does not fit"))
      tiny.position
    }
  }
}
