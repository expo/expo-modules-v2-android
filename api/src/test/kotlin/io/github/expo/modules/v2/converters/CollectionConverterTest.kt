package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.Union
import io.github.expo.modules.v2.args.BufferTrampolineArguments
import io.github.expo.modules.v2.args.Trampoline
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun collectionOf(element: TypeDescriptor.ObjectLike, isNullable: Boolean = false) =
  TypeDescriptor.Parametrized(Collection::class.java, isNullable, arrayOf(element))

private val INT = TypeDescriptor.Simple(Int::class.javaObjectType, false)
private val NULLABLE_STRING = TypeDescriptor.Simple(String::class.java, true)
private val URL_TYPE = TypeDescriptor.Simple(URL::class.java, false)

@Record
private data class Book(val id: Int, val title: String) : io.github.expo.modules.v2.records.Record

// A Collection variant told apart from a String one by the kind of JavaScript value.
@Union
private sealed interface Tags {
  @JvmInline
  value class Many(val values: Collection<String>) : Tags

  @JvmInline
  value class One(val value: String) : Tags
}

/** `Collection<T>` crosses as a JavaScript array, the same bridge value as a `List<T>`. */
class CollectionConverterTest {
  private val buffer = BinaryBuffer.allocate(4 * 1024)

  @Suppress("UNCHECKED_CAST")
  private fun converterOf(descriptor: TypeDescriptor.Parametrized) =
    descriptor.anyConverter as TypeConverter<Collection<*>>

  @Test
  fun `a Collection declaration resolves to its own converter with a List's type codes`() {
    val converter = collectionOf(URL_TYPE).anyConverter
    assertTrue(converter is CollectionConverter)
    assertContentEquals(intArrayOf(CppType.LIST.code, CppType.STRING.code), converter.typeCodes())
    // Never handed straight through: a Set or a map's values is not the bridge's List.
    assertFalse(converter.isPassthrough)
    assertFalse(collectionOf(INT).anyConverter.isPassthrough)
  }

  @Test
  fun `the nullable declaration marks its head and passes null both ways`() {
    val converter = converterOf(collectionOf(INT, isNullable = true))
    assertTrue(converter.typeCodes()[0] and CppType.NULLABLE != 0)
    assertNull(converter.toJni(null))
    assertNull(converter.fromJni(null))
  }

  @Test
  fun `a JavaScript array comes in as a List, converting each element`() {
    val urls = converterOf(collectionOf(URL_TYPE))
    assertEquals(
      listOf(URL("https://expo.dev/a"), URL("https://expo.dev/b")),
      urls.fromJni(listOf("https://expo.dev/a", "https://expo.dev/b")),
    )

    // Passthrough elements keep the bridge list itself.
    val bridge = listOf(1, 2, 3)
    assertSame(bridge, converterOf(collectionOf(INT)).fromJni(bridge))
  }

  @Test
  fun `any collection goes out as a List in its iteration order`() {
    val ints = converterOf(collectionOf(INT))
    assertEquals(listOf(3, 1, 2), ints.toJni(linkedSetOf(3, 1, 2)))
    assertEquals(listOf(10, 20), ints.toJni(linkedMapOf("a" to 10, "b" to 20).values))
    assertEquals(listOf(5, 6), ints.toJni(ArrayDeque(listOf(5, 6))))
    assertTrue(ints.toJni(linkedSetOf(1)) is List<*>)

    val urls = converterOf(collectionOf(URL_TYPE))
    assertEquals(
      listOf("https://expo.dev/a", "https://expo.dev/b"),
      urls.toJni(linkedSetOf(URL("https://expo.dev/a"), URL("https://expo.dev/b"))),
    )

    val strings = converterOf(collectionOf(NULLABLE_STRING))
    assertEquals(listOf("x", null), strings.toJni(linkedSetOf("x", null)))
  }

  @Test
  fun `the payload carries exactly a List's bytes`() {
    val cases: List<Pair<TypeDescriptor.ObjectLike, Collection<*>>> = listOf(
      INT to linkedSetOf(4, 5, 6),
      URL_TYPE to linkedMapOf(1 to URL("https://expo.dev/a"), 2 to URL("https://expo.dev/b")).values,
      NULLABLE_STRING to listOf("x", null, "y"),
    )
    for ((element, value) in cases) {
      val writer = buffer.duplicateView()
      converterOf(collectionOf(element)).writeToBuffer(writer, value)

      // The same bytes read back as the List declaration would read them.
      val asList = buffer.duplicateView()
      asList.limit = writer.position
      val listType = TypeDescriptor.Parametrized(List::class.java, false, arrayOf(element))
      assertEquals(value.toList(), listType.anyConverter.readFromBuffer(asList))

      val view = buffer.duplicateView()
      view.limit = writer.position
      assertEquals(value.toList(), converterOf(collectionOf(element)).readFromBuffer(view))
    }
  }

  /**
   * What a trampoline reads for a buffered argument, after [value] was written the way a buffered
   * result is: the generated code's own pair of seams, with no natives involved.
   */
  private fun bufferRoundTrip(descriptor: TypeDescriptor, value: Any?): Any? {
    val writer = buffer.duplicateView()
    val length = Trampoline.writeResultTo(writer, value, descriptor)
    val view = buffer.duplicateView()
    view.limit = length
    val args = BufferTrampolineArguments(view)
    return try {
      args.next(descriptor)
    } finally {
      args.finish()
    }
  }

  @Test
  fun `a decoded mutable declaration can grow, whatever its size and element type`() {
    val elements: List<Pair<TypeDescriptor.Simple, Any>> = listOf(
      INT to 1,
      TypeDescriptor.Simple(Long::class.javaObjectType, false) to 1L,
      TypeDescriptor.Simple(Float::class.javaObjectType, false) to 1f,
      TypeDescriptor.Simple(Double::class.javaObjectType, false) to 1.0,
      TypeDescriptor.Simple(Boolean::class.javaObjectType, false) to true,
      TypeDescriptor.Simple(String::class.java, false) to "one",
      URL_TYPE to URL("https://expo.dev/"),
    )
    // `MutableList<T>` and `MutableCollection<T>` erase to these classes.
    for (container in listOf(List::class.java, Collection::class.java)) {
      for ((element, sample) in elements) {
        val descriptor = TypeDescriptor.Parametrized(container, false, arrayOf(element))
        for (size in 0..2) {
          val value = List(size) { sample }
          val label = "${descriptor} with $size element(s)"

          // The payload: the numeric fast paths used to hand back `emptyList()`/`listOf(x)`.
          @Suppress("UNCHECKED_CAST")
          val buffered = bufferRoundTrip(descriptor, value) as MutableCollection<Any?>
          assertEquals(value, buffered.toList(), label)
          buffered.add(sample)
          assertEquals(size + 1, buffered.size, label)

          // The slot: the bridge hands over a `java.util.ArrayList` built by native code.
          @Suppress("UNCHECKED_CAST")
          val bridge = ArrayList(descriptor.anyConverter.toJni(value) as List<Any?>)
          @Suppress("UNCHECKED_CAST")
          val slot = descriptor.anyConverter.fromJni(bridge) as MutableCollection<Any?>
          slot.add(sample)
          assertEquals(size + 1, slot.size, label)
        }
      }
    }
  }

  @Test
  fun `a collection nested in a list or map is converted on its own, both ways`() {
    val intCollection = collectionOf(INT)
    val listOfCollections = TypeDescriptor.Parametrized(List::class.java, false, arrayOf(intCollection))
    val mapOfCollections = TypeDescriptor.Parametrized(
      Map::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(String::class.java, false), intCollection),
    )
    val groups = linkedMapOf("odd" to 1, "even" to 2, "odder" to 3)
    val listValue = listOf(linkedSetOf(3, 1), groups.values, ArrayDeque(listOf(9)))
    val mapValue = linkedMapOf("set" to linkedSetOf(5, 4), "values" to groups.values)
    val listExpected = listOf(listOf(3, 1), listOf(1, 2, 3), listOf(9))
    val mapExpected = mapOf("set" to listOf(5, 4), "values" to listOf(1, 2, 3))

    // The slot: every inner container reaches the bridge as a List - a Set handed through
    // unconverted would fail the JNI List slot.
    val listBridge = listOfCollections.anyConverter.toJni(listValue) as List<*>
    assertEquals(listExpected, listBridge)
    assertTrue(listBridge.all { it is List<*> }, "inner values crossed as ${listBridge.map { it?.javaClass }}")
    assertEquals(listExpected, listOfCollections.anyConverter.fromJni(listBridge))

    val mapBridge = mapOfCollections.anyConverter.toJni(mapValue) as Map<*, *>
    assertEquals(mapExpected, mapBridge)
    assertTrue(mapBridge.values.all { it is List<*> }, "inner values crossed as ${mapBridge.values.map { it?.javaClass }}")
    assertEquals(mapExpected, mapOfCollections.anyConverter.fromJni(mapBridge))

    // The payload.
    assertEquals(listExpected, bufferRoundTrip(listOfCollections, listValue))
    assertEquals(mapExpected, bufferRoundTrip(mapOfCollections, mapValue))
  }

  @Test
  fun `a collection of records converts each record, both ways`() {
    val books = collectionOf(TypeDescriptor.Simple(Book::class.java, false))
    val value = linkedSetOf(Book(1, "Dune"), Book(2, "Emma"))

    val bridge = books.anyConverter.toJni(value) as List<*>
    assertEquals(listOf(mapOf("id" to 1, "title" to "Dune"), mapOf("id" to 2, "title" to "Emma")), bridge)
    assertEquals(value.toList(), books.anyConverter.fromJni(bridge))

    assertEquals(value.toList(), bufferRoundTrip(books, value))
  }

  @Test
  fun `a union's Collection variant crosses the payload as an array`() {
    val tags = TypeDescriptor.Simple(Tags::class.java, false)
    val many = bufferRoundTrip(tags, Tags.Many(linkedSetOf("b", "a")))
    assertTrue(many is Tags.Many, "decoded $many")
    assertEquals(listOf("b", "a"), many.values.toList())
    assertEquals(Tags.One("x"), bufferRoundTrip(tags, Tags.One("x")))
  }

  @Test
  fun `the nullable declaration keeps its presence byte on the payload`() {
    val converter = converterOf(collectionOf(INT, isNullable = true))
    val writer = buffer.duplicateView()
    converter.writeToBuffer(writer, null)
    assertEquals(1, writer.position)
    val view = buffer.duplicateView()
    view.limit = writer.position
    assertNull(converter.readFromBuffer(view))
  }
}
