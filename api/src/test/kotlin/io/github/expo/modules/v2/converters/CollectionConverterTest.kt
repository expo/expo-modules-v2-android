package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
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
