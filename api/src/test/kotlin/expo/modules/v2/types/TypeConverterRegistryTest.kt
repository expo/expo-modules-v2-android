package expo.modules.v2.types

import expo.modules.v2.converters.ArrayConverter
import expo.modules.v2.converters.DurationConverter
import expo.modules.v2.converters.IntArrayConverter
import expo.modules.v2.converters.SetConverter
import expo.modules.v2.converters.TypeConverter
import expo.modules.v2.converters.UriConverter
import expo.modules.v2.converters.UrlConverter
import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

private class Unconverted

/** Pins the converters resolved by simple and parametrized descriptors. */
class TypeConverterRegistryTest {
  @Test
  fun `resolves the built-in table without registration`() {
    assertTrue(TypeDescriptor.Simple(URL::class.java, false).converter is UrlConverter)
    assertTrue(TypeDescriptor.Simple(URI::class.java, false).converter is UriConverter)
    assertTrue(TypeDescriptor.Simple(Duration::class.java, false).converter is DurationConverter)
  }

  @Test
  fun `a class's converter is built once per declared nullability`() {
    val plain = TypeDescriptor.Simple(URL::class.java, isNullable = false).converter
    val nullable = TypeDescriptor.Simple(URL::class.java, isNullable = true).converter
    assertSame(plain, TypeDescriptor.Simple(URL::class.java, false).converter, "must be cached")
    assertSame(nullable, TypeDescriptor.Simple(URL::class.java, true).converter, "must be cached")
    assertNotSame(plain, nullable, "each nullability states its own declaration")
    assertEquals(false, plain.isNullable)
    assertEquals(true, nullable.isNullable)
  }

  @Test
  fun `a class no convention covers is rejected`() {
    listOf(Unconverted::class.java, StringBuilder::class.java).forEach { type ->
     assertFails {
        TypeDescriptor.Simple(type, false).converter
      }
    }
  }

  @Test
  fun `simple descriptors keep distinct converters for each nullability`() {
    val plain = TypeDescriptor.Simple(String::class.java, false).converter
    assertNotSame(plain, TypeDescriptor.Simple(String::class.java, true).converter)
  }

  @Test
  fun `an object array requires a parametrized descriptor`() {
    val uris = TypeDescriptor.Parametrized(
      Array<URI>::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(URI::class.java, false)),
    ).converter
    assertTrue(uris is ArrayConverter<*>)
    assertContentEquals(intArrayOf(CppType.LIST.code, CppType.STRING.code), uris.codes.values)

    @Suppress("UNCHECKED_CAST")
    val asAny = uris as TypeConverter<Array<URI>>
    val value = arrayOf(URI("https://expo.dev/home"))
    assertEquals(listOf("https://expo.dev/home"), asAny.toJni(value))
    assertTrue(value.contentEquals(assertNotNull(asAny.fromJni(listOf("https://expo.dev/home")))))
    // A primitive array keeps its own leaf kind rather than becoming an object array, and each
    // Kotlin array type has a concrete descriptor.
    assertTrue(
      TypeDescriptor.IntArray(false).converter is IntArrayConverter,
    )
  }

  @Test
  fun `a parametrized array declaration states what its class cannot`() {
    // `Array<String>` and `Array<String?>` are one class, so nullable elements need a parameter.
    val nullableElements = TypeDescriptor.Parametrized(
      Array<String>::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(String::class.java, true)),
    ).converter
    assertTrue(nullableElements is ArrayConverter<*>)
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.STRING.code or CppType.NULLABLE),
      nullableElements.codes.values,
    )

    @Suppress("UNCHECKED_CAST")
    val asAny = nullableElements as TypeConverter<Array<String?>>
    val withNull = arrayOf("one", null)
    assertEquals(listOf("one", null), asAny.toJni(withNull))
    val decoded = assertNotNull(asAny.fromJni(listOf("one", null)))
    assertTrue(withNull.contentEquals(decoded))
    // The array is what the reflected element class says, not Object[].
    assertEquals(String::class.java, decoded.javaClass.componentType)
  }

  @Test
  fun `a Set declaration resolves structurally like a List`() {
    val urls = TypeDescriptor.Parametrized(
      Set::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(URL::class.java, false)),
    ).converter
    assertTrue(urls is SetConverter<*>)
    assertContentEquals(intArrayOf(CppType.LIST.code, CppType.STRING.code), urls.codes.values)

    @Suppress("UNCHECKED_CAST")
    val asAny = urls as TypeConverter<Set<URL>>
    assertEquals(listOf("https://expo.dev/"), asAny.toJni(setOf(URL("https://expo.dev/"))))
    // JavaScript array order is kept, and duplicates collapse.
    assertEquals(
      linkedSetOf(URL("https://expo.dev/a"), URL("https://expo.dev/b")),
      asAny.fromJni(listOf("https://expo.dev/a", "https://expo.dev/b", "https://expo.dev/a")),
    )
  }

  @Test
  fun `nullable structural containers carry their own nullability`() {
    listOf(
      TypeDescriptor.Parametrized(
        Set::class.java,
        true,
        arrayOf(TypeDescriptor.Simple(String::class.java, false)),
      ),
      TypeDescriptor.Parametrized(
        Array<URI>::class.java,
        true,
        arrayOf(TypeDescriptor.Simple(URI::class.java, false)),
      ),
    ).forEach { descriptor ->
      val converter = descriptor.converter
      assertTrue(converter.isNullable, descriptor.toString())
      assertTrue(
        converter.codes.isNullable,
        "$descriptor must mark its head nullable",
      )
      @Suppress("UNCHECKED_CAST")
      assertNull((converter as TypeConverter<Any>).toJni(null), descriptor.toString())
    }
  }
}
