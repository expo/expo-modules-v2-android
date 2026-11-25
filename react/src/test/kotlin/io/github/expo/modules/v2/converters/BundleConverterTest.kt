package io.github.expo.modules.v2.converters

import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.TypeDescriptor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class Mode { FAST }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundleConverterTest {
  private val converter = BundleConverter(isNullable = false)
  private val nullableConverter = BundleConverter(isNullable = true)

  /** What a `Map<String, Any?>` declaration resolves to: the bridge a Bundle must match. */
  private val dynamicMap = TypeDescriptor.Parametrized(
    Map::class.java,
    false,
    arrayOf(TypeDescriptor.Simple(String::class.java, false), TypeDescriptor.Simple(Any::class.java, true)),
  ).converter

  @Test
  fun `the registry resolves Bundle to its converter`() {
    assertIs<BundleConverter>(TypeDescriptor.Simple(Bundle::class.java, false).converter)
    assertIs<BundleConverter>(TypeDescriptor.Simple(Bundle::class.java, true).converter)
  }

  @Test
  fun `a Bundle has the type codes of a map of dynamic values`() {
    assertContentEquals(dynamicMap.typeCodes(), converter.typeCodes())
  }

  @Test
  fun `a JavaScript object becomes a Bundle`() {
    val bundle = converter.fromJni(
      mapOf(
        "name" to "expo",
        "count" to 3.0,
        "enabled" to true,
        "missing" to null,
        "nested" to mapOf("inner" to "value"),
        "items" to listOf("a", 1.0, null, mapOf("k" to false), listOf(2.0)),
        "bytes" to byteArrayOf(1, 2),
      )
    )!!

    assertEquals("expo", bundle.getString("name"))
    assertEquals(3.0, bundle.getDouble("count"))
    assertEquals(true, bundle.getBoolean("enabled"))
    assertTrue(bundle.containsKey("missing"))
    assertNull(bundle.getString("missing"))
    assertEquals("value", bundle.getBundle("nested")!!.getString("inner"))
    assertContentEquals(byteArrayOf(1, 2), bundle.getByteArray("bytes"))

    @Suppress("DEPRECATION")
    val items = bundle.getSerializable("items")
    assertIs<ArrayList<*>>(items)
    assertEquals(listOf("a", 1.0, null), items.take(3))
    assertEquals(false, assertIs<Bundle>(items[3]).getBoolean("k"))
    assertEquals(listOf(2.0), items[4])
  }

  @Test
  fun `a Bundle becomes a JavaScript object`() {
    val nested = Bundle().apply { putInt("depth", 2) }
    val bundle = Bundle().apply {
      putString("string", "s")
      putInt("int", 1)
      putLong("long", 2L)
      putFloat("float", 0.5f)
      putDouble("double", 1.5)
      putBoolean("bool", true)
      putByte("byte", 7)
      putShort("short", 8)
      putChar("char", 'c')
      putCharSequence("chars", StringBuilder("sb"))
      putString("nothing", null)
      putBundle("nested", nested)
      putIntArray("ints", intArrayOf(1, 2))
      putStringArray("strings", arrayOf("x", "y"))
      putShortArray("shorts", shortArrayOf(3))
      putCharArray("charArray", charArrayOf('a', 'b'))
      putParcelableArrayList("bundles", arrayListOf(nested))
      putStringArrayList("stringList", arrayListOf("p"))
      putSerializable("mode", Mode.FAST)
      putParcelable("uri", Uri.parse("https://expo.dev/a"))
    }

    @Suppress("UNCHECKED_CAST")
    val map = converter.toJni(bundle) as Map<String, Any?>
    val expectedNested = mapOf("depth" to 2)

    assertEquals("s", map["string"])
    assertEquals(1, map["int"])
    assertEquals(2L, map["long"])
    assertEquals(0.5f, map["float"])
    assertEquals(1.5, map["double"])
    assertEquals(true, map["bool"])
    assertEquals(7, map["byte"])
    assertEquals(8, map["short"])
    assertEquals("c", map["char"])
    assertEquals("sb", map["chars"])
    assertTrue(map.containsKey("nothing"))
    assertNull(map["nothing"])
    assertEquals(expectedNested, map["nested"])
    assertContentEquals(intArrayOf(1, 2), map["ints"] as IntArray)
    assertEquals(listOf("x", "y"), map["strings"])
    assertEquals(listOf(3), map["shorts"])
    assertEquals(listOf("a", "b"), map["charArray"])
    assertEquals(listOf(expectedNested), map["bundles"])
    assertEquals(listOf("p"), map["stringList"])
    assertEquals("FAST", map["mode"])
    assertEquals("https://expo.dev/a", map["uri"])
  }

  @Test
  fun `a value with no JavaScript form is refused by its key`() {
    val bundle = Bundle().apply { putParcelable("frame", Rect(0, 0, 1, 1)) }
    val error = assertFailsWith<IllegalArgumentException> { converter.toJni(bundle) }
    assertTrue("\"frame\"" in error.message!!, error.message)
  }

  @Test
  fun `null follows the declared nullability`() {
    assertNull(nullableConverter.fromJni(null))
    assertNull(nullableConverter.toJni(null))
    assertFailsWith<IllegalArgumentException> { converter.fromJni(null) }
  }

  @Test
  fun `an object round-trips through a Bundle`() {
    val map = mapOf(
      "a" to 1.0,
      "b" to listOf(true, null, mapOf("c" to "d")),
      "e" to mapOf("f" to null),
    )
    assertEquals(map, converter.toJni(converter.fromJni(map)))
  }

  @Test
  fun `the buffer layout is the one a map of dynamic values reads`() {
    val bundle = converter.fromJni(mapOf("a" to 1.0, "b" to listOf("x", mapOf("y" to true))))!!
    val expected = converter.toJni(bundle)

    val buffer = BinaryBuffer.allocate(4 * 1024)
    val writer = buffer.duplicateView()
    converter.writeToBuffer(writer, bundle)
    val length = writer.position

    val asMap = buffer.duplicateView().apply { limit = length }
    assertEquals(expected, dynamicMap.readFromBuffer(asMap))

    val asBundle = buffer.duplicateView().apply { limit = length }
    assertEquals(expected, converter.toJni(converter.readFromBuffer(asBundle)))
  }
}
