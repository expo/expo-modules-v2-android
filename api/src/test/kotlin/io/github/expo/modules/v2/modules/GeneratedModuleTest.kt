package io.github.expo.modules.v2.modules

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.types.CppType
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

@Record
private data class Pt(val x: Double, val y: Double) : io.github.expo.modules.v2.records.Record

@Record(bufferSafe = false)
private data class Tainted(val value: Any?) : io.github.expo.modules.v2.records.Record

/**
 * Pins the transport the compiler plugin picks for every value shape, as the code stream the registry
 * actually ships to C++.
 *
 * The rule under test is one sentence: buffer a value whenever the wire format allows it, except a
 * primitive array. `testData/box/moduleValueTypes.kt` pins the resulting JVM signatures; this pins
 * the declarations they were derived from, which is what `ExpectedTypeDecoder` reads.
 */
class GeneratedModuleTest {
  private val buffered = CppType.USES_BUFFER
  private val nullable = CppType.NULLABLE

  @ExpoModule(name = "Shapes")
  private class Shapes : Module() {
    @JS fun unboxed(a: Int, b: Long, c: Float, d: Double, e: Boolean): Int = a
    @JS fun boxed(a: Int?, b: Double?, c: Boolean?): Int? = a
    @JS fun strings(a: String, b: String?): String = a
    @JS fun arrays(a: IntArray, b: DoubleArray?): Int = a.size
    @JS fun dynamic(a: Any?, b: JavaScriptValue): Int = a.hashCode()
    @JS fun unit(a: Int) = Unit
    @JS fun lists(a: List<Int>, b: List<String?>): Int = a.size
    @JS fun maps(a: Map<String, Int>): Int = a.size
    @JS fun sets(a: Set<String>): Int = a.size
    @JS fun objectArrays(a: Array<String>): Int = a.size
    @JS fun converted(a: URL, b: Duration, c: Duration?): String = a.host
    @JS fun records(a: Pt, b: Tainted): Int = 0
    @JS fun recordLists(a: List<Pt>, b: List<Tainted>): Int = a.size
    @JS @BufferMode(Buffer.NO) fun optedOut(a: String, b: List<Int>): Int = a.length
    @JS fun optedIn(@BufferMode(Buffer.YES) a: IntArray): Int = a.size
    @JS @BufferMode(returns = Buffer.NO) fun plainReturn(a: Int): String = "$a"
  }

  private val definition = ModuleBuilder().also { Shapes().`define$ExpoModulesV2`(it) }

  private fun args(jsName: String): List<IntArray> =
    definition.functions.single { it.jsName == jsName }.argTypes.toList()

  private fun returns(jsName: String): IntArray =
    definition.functions.single { it.jsName == jsName }.returnType

  private fun methodName(jsName: String): String =
    definition.functions.single { it.jsName == jsName }.methodName

  @Test
  fun `an unboxed scalar keeps its register-width slot`() {
    assertContentEquals(
      listOf(
        intArrayOf(CppType.INT.code),
        intArrayOf(CppType.LONG.code),
        intArrayOf(CppType.FLOAT.code),
        intArrayOf(CppType.DOUBLE.code),
        intArrayOf(CppType.BOOLEAN.code),
      ).map { it.toList() },
      args("unboxed").map { it.toList() },
    )
    // Nothing to buffer and nothing to convert, so the bridge calls the method itself.
    assertEquals("unboxed", methodName("unboxed"))
  }

  @Test
  fun `a nullable scalar boxes and rides the buffer`() {
    assertContentEquals(
      listOf(
        intArrayOf(CppType.BOX_INT.code or nullable or buffered),
        intArrayOf(CppType.BOX_DOUBLE.code or nullable or buffered),
        intArrayOf(CppType.BOX_BOOLEAN.code or nullable or buffered),
      ).map { it.toList() },
      args("boxed").map { it.toList() },
    )
    assertContentEquals(
      intArrayOf(CppType.BOX_INT.code or nullable or buffered).toList(),
      returns("boxed").toList(),
    )
  }

  @Test
  fun `a String rides the buffer in both nullabilities`() {
    assertContentEquals(
      listOf(
        intArrayOf(CppType.STRING.code or buffered),
        intArrayOf(CppType.STRING.code or nullable or buffered),
      ).map { it.toList() },
      args("strings").map { it.toList() },
    )
  }

  @Test
  fun `a primitive array stays in its slot, because a bulk region copy already wins`() {
    assertContentEquals(
      listOf(
        intArrayOf(CppType.INT_ARRAY.code),
        intArrayOf(CppType.DOUBLE_ARRAY.code or nullable),
      ).map { it.toList() },
      args("arrays").map { it.toList() },
    )
  }

  @Test
  fun `Any and a JSI handle have no binary encoding, so they never buffer`() {
    assertContentEquals(
      listOf(
        intArrayOf(CppType.ANY.code or nullable),
        intArrayOf(CppType.JS_VALUE.code),
      ).map { it.toList() },
      args("dynamic").map { it.toList() },
    )
    assertEquals("dynamic", methodName("dynamic"))
  }

  @Test
  fun `a Unit return carries no value to encode`() {
    assertContentEquals(intArrayOf(CppType.UNIT.code).toList(), returns("unit").toList())
  }

  @Test
  fun `a container buffers, and its elements box`() {
    assertContentEquals(
      listOf(
        // head + element, the element boxed because Parametrized.params is ObjectLike
        intArrayOf(CppType.LIST.code or buffered, CppType.BOX_INT.code),
        intArrayOf(CppType.LIST.code or buffered, CppType.STRING.code or nullable),
      ).map { it.toList() },
      args("lists").map { it.toList() },
    )
    assertContentEquals(
      intArrayOf(CppType.MAP.code or buffered, CppType.BOX_INT.code).toList(),
      args("maps").single().toList(),
    )
    // A Set and an object Array both bridge as a list.
    assertContentEquals(
      intArrayOf(CppType.LIST.code or buffered, CppType.STRING.code).toList(),
      args("sets").single().toList(),
    )
    assertContentEquals(
      intArrayOf(CppType.LIST.code or buffered, CppType.STRING.code).toList(),
      args("objectArrays").single().toList(),
    )
  }

  @Test
  fun `a converted leaf follows the transport of its bridge type`() {
    val converted = args("converted")
    // URL bridges as a String, which the buffer carries faster.
    assertContentEquals(
      intArrayOf(CppType.STRING.code or buffered).toList(),
      converted[0].toList(),
    )
    // A non-null Duration bridges as an unboxed Double, which the buffer cannot carry at all.
    assertContentEquals(intArrayOf(CppType.DOUBLE.code).toList(), converted[1].toList())
    // Nullable, it boxes — and then it can.
    assertContentEquals(
      intArrayOf(CppType.BOX_DOUBLE.code or nullable or buffered).toList(),
      converted[2].toList(),
    )
  }

  @Test
  fun `a record buffers only when it declares itself buffer-safe`() {
    val records = args("records")
    assertEquals(CppType.RECORD.code or buffered, records[0][0])
    assertEquals(CppType.RECORD.code, records[1][0])
    // The schema id follows the head code, so both are two ints wide.
    assertEquals(2, records[0].size)
    assertEquals(2, records[1].size)

    // Taint is transitive through a container.
    val lists = args("recordLists")
    assertEquals(CppType.LIST.code or buffered, lists[0][0])
    assertEquals(CppType.LIST.code, lists[1][0])
  }

  @Test
  fun `Buffer NO puts a value back in its slot`() {
    assertContentEquals(
      listOf(
        intArrayOf(CppType.STRING.code),
        intArrayOf(CppType.LIST.code, CppType.BOX_INT.code),
      ).map { it.toList() },
      args("optedOut").map { it.toList() },
    )
    // Both values pass straight through, so opting out removes the trampoline entirely.
    assertEquals("optedOut", methodName("optedOut"))
  }

  @Test
  fun `Buffer YES moves a primitive array onto the payload`() {
    assertContentEquals(
      intArrayOf(CppType.INT_ARRAY.code or buffered).toList(),
      args("optedIn").single().toList(),
    )
    assertTrue(methodName("optedIn").endsWith("__trampoline\$ExpoModulesV2"))
  }

  @Test
  fun `BufferMode returns overrides the return value alone`() {
    assertContentEquals(intArrayOf(CppType.INT.code).toList(), args("plainReturn").single().toList())
    assertContentEquals(intArrayOf(CppType.STRING.code).toList(), returns("plainReturn").toList())
  }

  @ExpoModule(name = "Props")
  private class Props : Module() {
    @JS val version: String = "1.0"
    @JS var label: String = "expo"
    @JS var count: Int = 1
    @JS val isReady: Boolean = true
    @JS var homepage: URL = URL("https://expo.dev")
  }

  @Test
  fun `a property exports its accessors, and a var exports both`() {
    val props = ModuleBuilder().also { Props().`define$ExpoModulesV2`(it) }.properties
      .associateBy { it.jsName }

    // A String is read out of a JNI slot, which needs no trampoline at all.
    val version = props.getValue("version")
    assertEquals(CppType.STRING.code, version.getterType.single())
    assertEquals("getVersion", version.getterName)
    assertNull(version.setterName)
    assertNull(version.setterType)

    // The same String written the other way rides the buffer, so the pair splits: the setter is a
    // trampoline reading the payload, the getter a plain forwarding method returning the slot.
    val label = props.getValue("label")
    assertEquals(CppType.STRING.code, label.getterType.single())
    assertEquals(CppType.STRING.code or buffered, requireNotNull(label.setterType).single())
    assertEquals("getLabel__trampoline\$ExpoModulesV2", label.getterName)
    assertEquals("setLabel__trampoline\$ExpoModulesV2", label.setterName)

    // An unboxed Int needs neither, so the Kotlin accessors are exported directly.
    val count = props.getValue("count")
    assertEquals(CppType.INT.code, count.getterType.single())
    assertEquals(CppType.INT.code, requireNotNull(count.setterType).single())
    assertEquals("getCount", count.getterName)
    assertEquals("setCount", count.setterName)

    // Kotlin's `is` prefix rule survives: no `getIsReady`.
    assertEquals("isReady", props.getValue("isReady").getterName)

    // A converted value: the `is`-stripping and capitalisation happen on the trampoline base too.
    val homepage = props.getValue("homepage")
    assertEquals("getHomepage__trampoline\$ExpoModulesV2", homepage.getterName)
    assertEquals("setHomepage__trampoline\$ExpoModulesV2", homepage.setterName)
  }
}
