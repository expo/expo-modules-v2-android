package io.github.expo.modules.v2.converters

import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.types.TypeDescriptor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class Mode { FAST }

@Record
class Extras(val bundle: Bundle, val label: String)

@RunWith(RobolectricTestRunner::class)
class BundleConverterTest {
  private val converter = BundleConverter(isNullable = false)
  private val nullableConverter = BundleConverter(isNullable = true)

  private val string = TypeDescriptor.Simple(String::class.java, false)
  private val bundleType = TypeDescriptor.Simple(Bundle::class.java, false)

  /** What a `Map<String, Any?>` declaration resolves to: the bridge a Bundle must match. */
  private val dynamicMap = TypeDescriptor.Parametrized(
    Map::class.java,
    false,
    arrayOf(string, TypeDescriptor.Simple(Any::class.java, true)),
  ).converter

  /** A resolved converter, typed so that values can be handed to it. */
  @Suppress("UNCHECKED_CAST")
  private val TypeConverter<*>.untyped: TypeConverter<Any?> get() = this as TypeConverter<Any?>

  private fun TypeConverter<*>.roundTripBuffer(value: Any?): Any? {
    val buffer = BinaryBuffer.allocate(4 * 1024)
    val writer = buffer.duplicateView()
    untyped.writeToBuffer(writer, value)
    return readFromBuffer(buffer.duplicateView().apply { limit = writer.position })
  }

  @Suppress("UNCHECKED_CAST")
  private fun toJs(bundle: Bundle): Map<String, Any?> = converter.toJni(bundle) as Map<String, Any?>

  private fun refusal(bundle: Bundle): String =
    assertFailsWith<IllegalArgumentException> { converter.toJni(bundle) }.message!!

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
  fun `a JavaScript number is a Double, so getInt finds only the default`() {
    // Native code hands every dynamic number over as a Double: this map is what it builds for
    // `{ count: 5 }`.
    val bundle = converter.fromJni(mapOf("count" to 5.0))!!
    assertEquals(5.0, bundle.getDouble("count"))
    assertEquals(0, bundle.getInt("count"))
    assertEquals(0L, bundle.getLong("count"))
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
      putLongArray("longs", longArrayOf(3L))
      putFloatArray("floats", floatArrayOf(0.25f))
      putDoubleArray("doubles", doubleArrayOf(0.75))
      putBooleanArray("bools", booleanArrayOf(true, false))
      putByteArray("bytes", byteArrayOf(9))
      putStringArray("strings", arrayOf("x", "y"))
      putShortArray("shorts", shortArrayOf(3))
      putCharArray("charArray", charArrayOf('a', 'b'))
      putParcelableArrayList("bundles", arrayListOf(nested))
      putStringArrayList("stringList", arrayListOf("p"))
      putSerializable("map", hashMapOf<Any, Any?>(1 to "one", "two" to nested))
      putSerializable("mode", Mode.FAST)
      putParcelable("uri", Uri.parse("https://expo.dev/a"))
    }

    val map = toJs(bundle)
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
    assertContentEquals(longArrayOf(3L), map["longs"] as LongArray)
    assertContentEquals(floatArrayOf(0.25f), map["floats"] as FloatArray)
    assertContentEquals(doubleArrayOf(0.75), map["doubles"] as DoubleArray)
    assertContentEquals(booleanArrayOf(true, false), map["bools"] as BooleanArray)
    assertContentEquals(byteArrayOf(9), map["bytes"] as ByteArray)
    assertEquals(listOf("x", "y"), map["strings"])
    assertEquals(listOf(3), map["shorts"])
    assertEquals(listOf("a", "b"), map["charArray"])
    assertEquals(listOf(expectedNested), map["bundles"])
    assertEquals(listOf("p"), map["stringList"])
    assertEquals(mapOf("1" to "one", "two" to expectedNested), map["map"])
    assertEquals("FAST", map["mode"])
    assertEquals("https://expo.dev/a", map["uri"])
  }

  @Test
  fun `a value with no JavaScript form is refused by its path`() {
    val top = Bundle().apply { putParcelable("frame", Rect(0, 0, 1, 1)) }
    assertTrue("at frame " in refusal(top), refusal(top))

    val inner = Bundle().apply { putBundle("view", Bundle().apply { putParcelable("frame", Rect()) }) }
    assertTrue("at view.frame " in refusal(inner), refusal(inner))

    val inList = Bundle().apply { putSerializable("items", arrayListOf<Any?>("ok", Rect())) }
    assertTrue("at items[1] " in refusal(inList), refusal(inList))

    val inListBundle = Bundle().apply {
      putParcelableArrayList("views", arrayListOf(Bundle().apply { putParcelable("frame", Rect()) }))
    }
    assertTrue("at views[0].frame " in refusal(inListBundle), refusal(inListBundle))
  }

  @Test
  fun `map keys that read the same as strings are refused`() {
    val bundle = Bundle().apply { putSerializable("ids", hashMapOf<Any, Any>(1 to "a", "1" to "b")) }
    val message = refusal(bundle)
    assertTrue("at ids " in message && "\"1\"" in message, message)
  }

  @Test
  fun `a Bundle that contains itself is refused, not overflowed`() {
    val direct = Bundle().apply { putBundle("self", this) }
    assertTrue("contains itself" in refusal(direct), refusal(direct))

    val outer = Bundle()
    outer.putBundle("inner", Bundle().apply { putBundle("outer", outer) })
    assertTrue("contains itself" in refusal(outer), refusal(outer))

    val list = ArrayList<Any?>()
    val throughList = Bundle().apply { putSerializable("list", list) }
    list.add(throughList)
    assertTrue("contains itself" in refusal(throughList), refusal(throughList))

    val map = HashMap<String, Any?>()
    map["map"] = map
    val throughMap = Bundle().apply { putSerializable("map", map) }
    assertTrue("at map.map contains itself" in refusal(throughMap), refusal(throughMap))

    val array = arrayOfNulls<Any>(1)
    array[0] = array
    val throughArray = Bundle().apply { putSerializable("array", array) }
    assertTrue("contains itself" in refusal(throughArray), refusal(throughArray))
  }

  @Test
  fun `a Bundle held in two places that do not contain each other converts in both`() {
    val shared = Bundle().apply { putString("k", "v") }
    val bundle = Bundle().apply {
      putBundle("first", shared)
      putBundle("second", shared)
      putParcelableArrayList("list", arrayListOf(shared, shared))
    }
    val expected = mapOf("k" to "v")
    assertEquals(
      mapOf("first" to expected, "second" to expected, "list" to listOf(expected, expected)),
      toJs(bundle),
    )
  }

  @Test
  fun `an object that contains itself is refused on the way in`() {
    val map = HashMap<String, Any?>()
    map["self"] = map
    val error = assertFailsWith<IllegalArgumentException> { converter.fromJni(map) }
    assertTrue("contains itself" in error.message!!, error.message)

    val list = ArrayList<Any?>()
    list.add(list)
    val throughList = assertFailsWith<IllegalArgumentException> { converter.fromJni(mapOf("list" to list)) }
    assertTrue("list[0]" in throughList.message!!, throughList.message)

    // Shared but acyclic is fine on the way in too.
    val shared = mapOf("k" to "v")
    val bundle = converter.fromJni(mapOf("a" to shared, "b" to listOf(shared, shared)))!!
    assertEquals("v", bundle.getBundle("a")!!.getString("k"))
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

  @Test
  fun `a nullable Bundle writes its presence byte on the buffer`() {
    assertNull(nullableConverter.roundTripBuffer(null))

    val bundle = converter.fromJni(mapOf("a" to "b"))!!
    assertEquals(mapOf("a" to "b"), nullableConverter.toJni(nullableConverter.roundTripBuffer(bundle) as Bundle))

    // The non-null form has no presence byte, so the two layouts differ by exactly one byte.
    val withPresence = BinaryBuffer.allocate(1024).duplicateView().also { nullableConverter.writeToBuffer(it, bundle) }
    val without = BinaryBuffer.allocate(1024).duplicateView().also { converter.writeToBuffer(it, bundle) }
    assertEquals(without.position + 1, withPresence.position)
    assertFailsWith<IllegalArgumentException> { converter.writeToBuffer(BinaryBuffer.allocate(16), null) }
  }

  @Test
  fun `Bundles inside a List and a Map convert element by element`() {
    val list = TypeDescriptor.Parametrized(List::class.java, false, arrayOf(bundleType)).converter.untyped
    val map = TypeDescriptor.Parametrized(Map::class.java, false, arrayOf(string, bundleType)).converter.untyped

    val object1 = mapOf("a" to 1.0)
    val bundles = list.fromJni(listOf(object1)) as List<*>
    assertEquals(1.0, assertIs<Bundle>(bundles.single()).getDouble("a"))
    assertEquals(listOf(object1), list.toJni(bundles))

    val keyed = map.fromJni(mapOf("k" to object1)) as Map<*, *>
    assertEquals(1.0, assertIs<Bundle>(keyed["k"]).getDouble("a"))
    assertEquals(mapOf("k" to object1), map.toJni(keyed))

    // The buffer, element by element too.
    assertEquals(listOf(object1), list.toJni(list.roundTripBuffer(bundles)))
  }

  @Test
  fun `a Bundle record field converts through the record`() {
    val record = TypeDescriptor.Simple(Extras::class.java, false).converter.untyped

    val extras = record.fromJni(mapOf("bundle" to mapOf("a" to "b"), "label" to "l")) as Extras
    assertEquals("b", extras.bundle.getString("a"))
    assertEquals("l", extras.label)

    @Suppress("UNCHECKED_CAST")
    val back = record.toJni(extras) as Map<String, Any?>
    assertEquals(mapOf("a" to "b"), back["bundle"])
    assertEquals("l", back["label"])

    // The record crosses the buffer with its Bundle field too.
    val again = record.roundTripBuffer(extras) as Extras
    assertEquals("b", again.bundle.getString("a"))
  }
}
