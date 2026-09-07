package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.records.readers.BufferRecordReader
import io.github.expo.modules.v2.records.writers.BufferRecordWriter
import io.github.expo.modules.v2.args.BufferTrampolineArguments
import io.github.expo.modules.v2.modules.ModuleBuilder
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.records.codecFor
import io.github.expo.modules.v2.converters.ArrayConverter
import io.github.expo.modules.v2.converters.BooleanArrayConverter
import io.github.expo.modules.v2.converters.ByteArrayConverter
import io.github.expo.modules.v2.converters.DoubleArrayConverter
import io.github.expo.modules.v2.converters.DurationConverter
import io.github.expo.modules.v2.converters.FileConverter
import io.github.expo.modules.v2.converters.FloatArrayConverter
import io.github.expo.modules.v2.converters.IntArrayConverter
import io.github.expo.modules.v2.converters.LongArrayConverter
import io.github.expo.modules.v2.converters.PathConverter
import io.github.expo.modules.v2.converters.SetConverter
import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.converters.UriConverter
import io.github.expo.modules.v2.converters.UrlConverter
import io.github.expo.kolibri.binary.BinaryBuffer
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// A codec names the converter of the declaration it reads, nullability included.
private val urlConverter = UrlConverter(isNullable = false)
private val durationConverter = DurationConverter(isNullable = false)

@Record
private data class Link(val url: URL, val ttl: Duration?) : io.github.expo.modules.v2.records.Record

private val linkType = RecordRegistry.typeFor(codecFor<Link>())
private val urlType = AnyType(TypeDescriptor.Simple(URL::class.java, false))
private val durationType = AnyType(TypeDescriptor.Simple(Duration::class.java, false))

/** The `Set<element>` declaration a [io.github.expo.modules.v2.converters.SetConverter] is built from. */
private fun setDescriptor(
  element: TypeDescriptor.ObjectLike,
  isNullable: Boolean = false,
) = TypeDescriptor.Parametrized(Set::class.java, isNullable, arrayOf(element))

/** The `Array<element>` declaration an [io.github.expo.modules.v2.converters.ArrayConverter] is built from. */
private fun arrayDescriptor(
  arrayClass: Class<*>,
  element: TypeDescriptor.ObjectLike,
  isNullable: Boolean = false,
) = TypeDescriptor.Parametrized(arrayClass, isNullable, arrayOf(element))

/**
 * Pure-JVM half of the [TypeConverter] contract: converted record fields and converted trampoline
 * arguments/results with complex bridge types. Uses
 * [BinaryBuffer.allocate] and the `*To`/`argumentsOf` seams, so no natives are involved.
 */
class TypeConverterTest {
  private val buffer = BinaryBuffer.allocate(4 * 1024)

  @Test
  fun `converted record fields roundtrip through their bridge types`() {
    val link = Link(URL("https://expo.dev/"), 1500.milliseconds)
    val writer = buffer.duplicateView()
    val writer1 = BufferRecordWriter(writer)
    linkType.anyCodec.encode(link, writer1)

    val view = buffer.duplicateView()
    view.limit = writer.position
    assertEquals(link, linkType.codec.decode(BufferRecordReader(view)))
  }

  @Test
  fun `a nullable converted field roundtrips a null`() {
    val link = Link(URL("https://expo.dev/"), null)
    val writer = buffer.duplicateView()
    val writer1 = BufferRecordWriter(writer)
    linkType.anyCodec.encode(link, writer1)

    val view = buffer.duplicateView()
    view.limit = writer.position
    assertEquals(link, linkType.codec.decode(BufferRecordReader(view)))
  }

  @Test
  fun `converted elements roundtrip inside containers`() {
    // A container's element carries its own conversion; the codec applies it per value, in every
    // position — no dedicated container converter involved.
    val urls = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(URL::class.java, false)),
      ),
    )
    val value = listOf(URL("https://expo.dev/"), URL("https://docs.expo.dev/"))
    val writer = buffer.duplicateView()
    urls.descriptor.anyConverter.writeToBuffer(writer, value)

    val view = buffer.duplicateView()
    view.limit = writer.position
    assertEquals(value, urls.descriptor.anyConverter.readFromBuffer(view))

    val durations = AnyType(
      TypeDescriptor.Parametrized(
        Map::class.java,
        false,
        arrayOf(
          TypeDescriptor.Simple(String::class.java, false),
          TypeDescriptor.Simple(Duration::class.java, false),
        ),
      ),
    )
    val timeouts = mapOf("connect" to 1500.milliseconds, "read" to 250.milliseconds)
    val mapWriter = buffer.duplicateView()
    durations.descriptor.anyConverter.writeToBuffer(mapWriter, timeouts)

    val mapView = buffer.duplicateView()
    mapView.limit = mapWriter.position
    assertEquals(timeouts, durations.descriptor.anyConverter.readFromBuffer(mapView))
  }

  @Test
  fun `a converted-only signature accepts a trampoline`() {
    ModuleBuilder().function(
      "total",
      durationType,
      durationType,
      returns = durationType,
      methodName = "total__trampoline",
    )
  }

  @Test
  fun `built-in converters roundtrip their values`() {
    assertEquals(URL("https://expo.dev/path"), urlConverter.fromJni("https://expo.dev/path"))
    assertEquals("https://expo.dev/path", urlConverter.toJni(URL("https://expo.dev/path")))
    val unescapedUrl = "https://expo.dev/expo//%?^&/test"
    assertEquals(unescapedUrl, urlConverter.toJni(urlConverter.fromJni(unescapedUrl)))
    assertEquals(1500.milliseconds, durationConverter.fromJni(1.5))
    assertEquals(0.5, durationConverter.toJni(500.milliseconds))

    val uri = URI("https://expo.dev/path?q=expo")
    assertEquals(uri, UriConverter(isNullable = false).fromJni(uri.toString()))
    assertEquals(uri.toString(), UriConverter(isNullable = false).toJni(uri))

    val absoluteFile = File("/tmp/expo/file.txt")
    assertEquals(absoluteFile, FileConverter(isNullable = false).fromJni(absoluteFile.path))
    assertEquals(absoluteFile.absolutePath, FileConverter(isNullable = false).toJni(absoluteFile))

    val path = Paths.get("/tmp/expo/file.txt")
    assertEquals(path, PathConverter(isNullable = false).fromJni(path.toString()))
    assertEquals(path.toString(), PathConverter(isNullable = false).toJni(path))
  }

  @Test
  fun `a nullable built-in declaration rides a slot that can carry null`() {
    // The string-backed conversions state STRING, which is a JVM object either way...
    val nullableString = intArrayOf(CppType.STRING.code or CppType.NULLABLE)
    assertContentEquals(nullableString, UrlConverter(isNullable = true).codes.values)
    assertContentEquals(nullableString, UriConverter(isNullable = true).codes.values)
    assertContentEquals(nullableString, FileConverter(isNullable = true).codes.values)
    assertContentEquals(nullableString, PathConverter(isNullable = true).codes.values)
    // ...while a scalar-backed one boxes: an unboxed double slot has no null to hand over, and the
    // native decoder rejects a nullable scalar code.
    assertContentEquals(
      intArrayOf(CppType.DOUBLE.code),
      DurationConverter(isNullable = false).codes.values,
    )
    assertContentEquals(
      intArrayOf(CppType.BOX_DOUBLE.code or CppType.NULLABLE),
      DurationConverter(isNullable = true).codes.values,
    )
    assertNull(DurationConverter(isNullable = true).toJni(null))
    assertNull(DurationConverter(isNullable = true).fromJni(null))
  }

  @Test
  fun `set and object array converters compose direct and converted element types`() {
    val string = AnyType(TypeDescriptor.Simple(String::class.java, false))

    val stringSet = SetConverter<String>(setDescriptor(TypeDescriptor.Simple(String::class.java, false)))
    assertEquals(linkedSetOf("one", "two"), stringSet.fromJni(listOf("one", "two", "one")))
    assertEquals(listOf("one", "two"), stringSet.toJni(linkedSetOf("one", "two")))

    val nullableStringSet =
      SetConverter<String?>(setDescriptor(TypeDescriptor.Simple(String::class.java, true)))
    assertEquals(linkedSetOf("one", null), nullableStringSet.fromJni(listOf("one", null, "one")))
    assertEquals(listOf("one", null), nullableStringSet.toJni(linkedSetOf("one", null)))

    val strings = ArrayConverter<String>(
      arrayDescriptor(Array<String>::class.java, TypeDescriptor.Simple(String::class.java, false)),
    )
    assertTrue(arrayOf("a", "b").contentEquals(strings.fromJni(listOf("a", "b"))))
    assertEquals(listOf("a", "b"), strings.toJni(arrayOf("a", "b")))

    val boxedInts = ArrayConverter<Int>(
      arrayDescriptor(Array<Int>::class.java, TypeDescriptor.Simple(Int::class.javaObjectType, false)),
    )
    assertTrue(arrayOf(1, 2).contentEquals(boxedInts.fromJni(listOf(1, 2))))
    assertEquals(Array<Int>::class.java, assertNotNull(boxedInts.fromJni(listOf(1, 2))).javaClass)

    val nullableStrings = ArrayConverter<String?>(
      arrayDescriptor(Array<String>::class.java, TypeDescriptor.Simple(String::class.java, true)),
    )
    val stringsWithNull = arrayOf("one", null, "two")
    assertTrue(stringsWithNull.contentEquals(nullableStrings.fromJni(stringsWithNull.asList())))
    assertEquals(stringsWithNull.asList(), nullableStrings.toJni(stringsWithNull))
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.STRING.code or CppType.NULLABLE),
      nullableStrings.codes.values,
    )
  }

  @Test
  fun `set and array converters carry converted elements through their bridge list`() {
    // The element type carries its conversion: the bridge list applies it per value, so a URL
    // element crosses as its string form in every position.
    val urls = linkedSetOf(URL("https://expo.dev/home"), URL("https://expo.dev/docs"))
    val urlStrings = listOf("https://expo.dev/home", "https://expo.dev/docs")
    val urlSet = SetConverter<URL>(setDescriptor(TypeDescriptor.Simple(URL::class.java, false)))
    val urlSetBridge = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(URL::class.java, false)),
      ),
    )
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.STRING.code),
      urlSetBridge.codes.values,
    )
    assertEquals(urls, urlSet.fromJni(urlStrings))
    assertEquals(urlStrings, urlSet.toJni(urls))

    val nullableUrlArray = ArrayConverter<URL?>(
      arrayDescriptor(Array<URL>::class.java, TypeDescriptor.Simple(URL::class.java, true)),
    )
    val urlsWithNull = arrayOf(URL("https://expo.dev/"), null)
    val urlStringsWithNull = listOf("https://expo.dev/", null)
    assertTrue(urlsWithNull.contentEquals(nullableUrlArray.fromJni(urlStringsWithNull)))
    assertEquals(urlStringsWithNull, nullableUrlArray.toJni(urlsWithNull))
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.STRING.code or CppType.NULLABLE),
      nullableUrlArray.codes.values,
    )

    // End to end through the binary codec: the bridge list encodes STRING elements.
    val writer = buffer.duplicateView()
    urlSetBridge.descriptor.anyConverter.writeToBuffer(writer, urls.toList())
    val view = buffer.duplicateView()
    view.limit = writer.position
    assertEquals(
      urlStrings,
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(String::class.java, false)),
      ).anyConverter.readFromBuffer(view),
    )
  }

  @Test
  fun `a nullable object array declaration carries its presence byte`() {
    // Nullability of the array itself belongs to the declaration, so it rides the bridge list's
    // head code and a presence byte — the element codes stay untouched.
    val urls = ArrayConverter<URL>(
      arrayDescriptor(
        Array<URL>::class.java,
        TypeDescriptor.Simple(URL::class.java, false),
        isNullable = true,
      ),
    )
    assertContentEquals(
      intArrayOf(CppType.LIST.code or CppType.NULLABLE, CppType.STRING.code),
      urls.codes.values,
    )
    assertNull(urls.toJni(null))

    val nullWriter = buffer.duplicateView()
    urls.writeToBuffer(nullWriter, null)
    assertEquals(1, nullWriter.position)
    val nullView = buffer.duplicateView()
    nullView.limit = nullWriter.position
    assertNull(urls.readFromBuffer(nullView))

    val present = arrayOf(URL("https://expo.dev/home"), URL("https://expo.dev/docs"))
    val writer = buffer.duplicateView()
    urls.writeToBuffer(writer, present)
    val view = buffer.duplicateView()
    view.limit = writer.position
    assertTrue(present.contentEquals(urls.readFromBuffer(view)))

    // A nullable parametrized declaration resolves to the same codes.
    val declared = AnyType(
      arrayDescriptor(
        Array<URL>::class.java,
        TypeDescriptor.Simple(URL::class.java, false),
        isNullable = true,
      ),
    )
    assertContentEquals(urls.codes.values, declared.codes.values)
    assertTrue(declared.converter.isNullable)

    // A non-nullable declaration rejects the null instead of writing a presence byte.
    val nonNull = ArrayConverter<URL>(
      arrayDescriptor(Array<URL>::class.java, TypeDescriptor.Simple(URL::class.java, false)),
    )
    assertFailsWith<IllegalArgumentException> {
      nonNull.writeToBuffer(buffer.duplicateView(), null)
    }
  }

  @Test
  fun `primitive array converters cross their own Kotlin type unchanged`() {
    // A primitive array is described structurally, and each element type resolves to the converter
    // that states its Kotlin type.
    assertTrue(TypeDescriptor.IntArray(false).converter is IntArrayConverter)
    assertTrue(TypeDescriptor.LongArray(false).converter is LongArrayConverter)
    assertTrue(TypeDescriptor.FloatArray(false).converter is FloatArrayConverter)
    assertTrue(TypeDescriptor.DoubleArray(false).converter is DoubleArrayConverter)
    assertTrue(TypeDescriptor.BooleanArray(false).converter is BooleanArrayConverter)

    // The JNI slot is the array itself, so both conversions are the identity and only the buffer
    // pass does work — one bulk region copy per array.
    val ints = IntArrayConverter(isNullable = false)
    assertTrue(ints.isPassthrough)
    assertContentEquals(intArrayOf(CppType.INT_ARRAY.code), ints.codes.values)
    val intValues = intArrayOf(1, -2, 3)
    assertSame(intValues, ints.toJni(intValues))
    assertSame(intValues, ints.fromJni(intValues))
    assertTrue(intValues.contentEquals(roundTrip(ints, intValues)))

    val longs = LongArrayConverter(isNullable = false)
    assertContentEquals(intArrayOf(CppType.LONG_ARRAY.code), longs.codes.values)
    assertTrue(longArrayOf(1L, -2L).contentEquals(roundTrip(longs, longArrayOf(1L, -2L))))

    val floats = FloatArrayConverter(isNullable = false)
    assertContentEquals(intArrayOf(CppType.FLOAT_ARRAY.code), floats.codes.values)
    assertTrue(floatArrayOf(1.5f, -2.5f).contentEquals(roundTrip(floats, floatArrayOf(1.5f, -2.5f))))

    val doubles = DoubleArrayConverter(isNullable = false)
    assertContentEquals(intArrayOf(CppType.DOUBLE_ARRAY.code), doubles.codes.values)
    assertTrue(doubleArrayOf(1.5, -2.5).contentEquals(roundTrip(doubles, doubleArrayOf(1.5, -2.5))))

    val booleans = BooleanArrayConverter(isNullable = false)
    assertContentEquals(intArrayOf(CppType.BOOLEAN_ARRAY.code), booleans.codes.values)
    val flags = booleanArrayOf(true, false, true)
    assertTrue(flags.contentEquals(roundTrip(booleans, flags)))

    // ByteArray is declared directly just like every other primitive array.
    assertTrue(TypeDescriptor.ByteArray(false).converter is ByteArrayConverter)
    val bytes = ByteArrayConverter(isNullable = false)
    assertContentEquals(intArrayOf(CppType.BYTE_ARRAY.code), bytes.codes.values)
    assertTrue(byteArrayOf(1, -2).contentEquals(roundTrip(bytes, byteArrayOf(1, -2))))
  }

  @Test
  fun `a nullable primitive array declaration carries its presence byte`() {
    val declared = TypeDescriptor.IntArray(true).converter
    assertTrue(declared is IntArrayConverter)
    assertTrue(declared.isNullable)

    val ints = IntArrayConverter(isNullable = true)
    // A primitive array has no boxed form, so a nullable declaration keeps its own kind.
    assertContentEquals(
      intArrayOf(CppType.INT_ARRAY.code or CppType.NULLABLE),
      ints.codes.values,
    )
    assertNull(ints.toJni(null))
    assertNull(ints.fromJni(null))

    val nullWriter = buffer.duplicateView()
    ints.writeToBuffer(nullWriter, null)
    assertEquals(1, nullWriter.position)
    val nullView = buffer.duplicateView()
    nullView.limit = nullWriter.position
    assertNull(ints.readFromBuffer(nullView))

    val present = intArrayOf(7, 8)
    assertTrue(present.contentEquals(roundTrip(ints, present)))

    // The non-nullable declaration rejects the null instead of writing a presence byte.
    val nonNull = TypeDescriptor.IntArray(false).converter
    assertFailsWith<IllegalArgumentException> {
      nonNull.writeToBuffer(buffer.duplicateView(), null)
    }
    assertFailsWith<IllegalArgumentException> { nonNull.fromJni(null) }
  }

  /** [value] written by [converter] and read back by it — the buffer pass's own round trip. */
  private fun <T> roundTrip(converter: TypeConverter<T>, value: T): T? {
    val writer = buffer.duplicateView()
    converter.writeToBuffer(writer, value)
    val view = buffer.duplicateView()
    view.limit = writer.position
    return converter.readFromBuffer(view)
  }

  @Test
  fun `object array declarations reach their converter through a trampoline`() {
    val arrayType = AnyType(
      arrayDescriptor(Array<URL>::class.java, TypeDescriptor.Simple(URL::class.java, false)),
    )
    ModuleBuilder().function(
      "hosts",
      arrayType.buffered(),
      returns = arrayType.buffered(),
      methodName = "hosts__trampoline",
    )

    // The buffered argument and result agree byte for byte: one payload, read back by the same
    // declaration the writer used.
    val urls = arrayOf(URL("https://expo.dev/home"), URL("https://expo.dev/docs"))
    val writer = buffer.duplicateView()
    arrayType.descriptor.anyConverter.writeToBuffer(writer, urls)
    val length = writer.position
    val buf = buffer.duplicateView()
    buf.limit = length
    val args = BufferTrampolineArguments(buf)
    val decoded: Array<URL> = try {
      args.next(arrayType.descriptor)
    } finally {
      args.finish()
    }
    assertTrue(urls.contentEquals(decoded))
  }

  @Test
  fun `container-bridge converters cross the payload in one pass`() {
    val elementType = TypeDescriptor.Simple(URL::class.java, false)
    val setType = AnyType(setDescriptor(elementType))
    val urls = linkedSetOf(URL("https://expo.dev/home"), URL("https://expo.dev/docs"))

    val writer = buffer.duplicateView()
    setType.descriptor.anyConverter.writeToBuffer(writer, urls)

    // The one-pass override writes exactly the bridge list's bytes: STRING elements after a
    // size header.
    val asBridge = buffer.duplicateView()
    asBridge.limit = writer.position
    assertEquals(
      listOf("https://expo.dev/home", "https://expo.dev/docs"),
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(String::class.java, false)),
      ).anyConverter.readFromBuffer(asBridge),
    )

    val view = buffer.duplicateView()
    view.limit = writer.position
    assertEquals(urls, setType.descriptor.anyConverter.readFromBuffer(view))

    // The nullable declaration keeps its presence byte around the same payload.
    val nullableSet = AnyType(setDescriptor(elementType, isNullable = true))
    val nullWriter = buffer.duplicateView()
    nullableSet.descriptor.anyConverter.writeToBuffer(nullWriter, null)
    assertEquals(1, nullWriter.position)
    val nullView = buffer.duplicateView()
    nullView.limit = nullWriter.position
    assertNull(nullableSet.descriptor.anyConverter.readFromBuffer(nullView))

    val arrayType = AnyType(
      arrayDescriptor(Array<URL>::class.java, TypeDescriptor.Simple(URL::class.java, false)),
    )
    val array = arrayOf(URL("https://expo.dev/home"), URL("https://expo.dev/docs"))
    val arrayWriter = buffer.duplicateView()
    arrayType.descriptor.anyConverter.writeToBuffer(arrayWriter, array)
    val arrayView = buffer.duplicateView()
    arrayView.limit = arrayWriter.position
    assertTrue(array.contentEquals(arrayType.descriptor.anyConverter.readFromBuffer(arrayView) as Array<*>))
  }

}
