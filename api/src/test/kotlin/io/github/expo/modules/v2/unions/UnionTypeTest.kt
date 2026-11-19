package io.github.expo.modules.v2.unions

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.converters.typeCodes
import io.github.expo.modules.v2.Tag
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The annotation, not the marker interface of this package.
@io.github.expo.modules.v2.Union
private sealed interface Dimension {
  @JvmInline
  value class Points(val value: Double) : Dimension

  @Tag("auto")
  data object Auto : Dimension

  @Record
  data class Percent(val percent: Double) : Dimension
}

/** A union that holds itself. */
@io.github.expo.modules.v2.Union
private sealed interface Tree {
  @JvmInline
  value class Leaf(val value: Int) : Tree

  @JvmInline
  value class Node(val children: List<Tree>) : Tree
}

/** Two string variants: a string `URL` refuses goes to `String`. */
@io.github.expo.modules.v2.Union
private sealed interface Link {
  @JvmInline
  value class Address(val url: URL) : Link

  @JvmInline
  value class Text(val text: String) : Link
}

/** Refs native code cannot tell apart: what a ref carries is only seen by its conversion. */
@io.github.expo.modules.v2.Union
private sealed interface Refs {
  @JvmInline
  value class OfText(val ref: SharedRef<StringBuilder>) : Refs

  @JvmInline
  value class OfCount(val ref: SharedRef<AtomicInteger>) : Refs
}

private enum class Level(val value: Int) { LOW(0), HIGH(1) }

/** An Int-backed enum or any other Int. */
@io.github.expo.modules.v2.Union
private sealed interface Priority {
  @JvmInline
  value class Named(val level: Level) : Priority

  @JvmInline
  value class Custom(val value: Int) : Priority
}

/** `length, byte...`, as type codes spell a string. */
private fun utf8(value: String): IntArray {
  val bytes = value.encodeToByteArray()
  return intArrayOf(bytes.size, *IntArray(bytes.size) { bytes[it].toInt() and 0xFF })
}

/** `List<T>` of [element]. */
private fun listType(element: TypeDescriptor.ObjectLike) =
  TypeDescriptor.Parametrized(List::class.java, false, arrayOf(element))

private fun simple(type: Class<*>) = TypeDescriptor.Simple(type, false)

/** Pure-JVM union tests: what the converter writes, and what the runtime refuses. */
class UnionTypeTest {
  private val dimension = TypeDescriptor.Simple(Dimension::class.java, false).anyConverter

  private val buffer = BinaryBuffer.allocate(4 * 1024)

  private fun roundTrip(value: Any): Any? {
    val out = buffer.duplicateView()
    dimension.writeToBuffer(out, value)
    val view = buffer.duplicateView()
    view.limit = out.position
    return dimension.readFromBuffer(view)
  }

  @Test
  fun `each variant round-trips through the buffer`() {
    assertEquals(Dimension.Points(2.5), roundTrip(Dimension.Points(2.5)))
    assertEquals(Dimension.Auto, roundTrip(Dimension.Auto))
    assertEquals(Dimension.Percent(0.5), roundTrip(Dimension.Percent(0.5)))
  }

  @Test
  fun `the buffer carries the variant's index, then its value`() {
    val out = buffer.duplicateView()
    dimension.writeToBuffer(out, Dimension.Points(2.5))
    val view = buffer.duplicateView()
    view.limit = out.position
    assertEquals(0, view.getInt())
    assertEquals(2.5, view.getDouble())
    assertEquals(out.position, view.position)

    val literal = buffer.duplicateView()
    dimension.writeToBuffer(literal, Dimension.Auto)
    assertEquals(Int.SIZE_BYTES, literal.position, "an object variant carries no value")
  }

  @Test
  fun `the type codes spell out each variant`() {
    val percentCodes = TypeDescriptor.Simple(Dimension.Percent::class.java, false).converter.typeCodes()
    assertContentEquals(
      intArrayOf(
        CppType.UNION.code, 3, *utf8("Dimension"),
        CppType.DOUBLE.code,
        CppType.UNIT.code, *utf8("auto"),
        *percentCodes,
      ),
      dimension.typeCodes(),
    )
  }

  @Test
  fun `a union met again inside itself is a reference back to it`() {
    val tree = TypeDescriptor.Simple(Tree::class.java, false).anyConverter
    val treeCodes = intArrayOf(
      CppType.UNION.code, 2, *utf8("Tree"),
      CppType.INT.code,
      CppType.LIST.code, CppType.UNION_REF.code, 0,
    )
    assertContentEquals(treeCodes, tree.typeCodes())

    // Outside the union the list spells it out: its codes stand on their own.
    assertContentEquals(
      intArrayOf(CppType.LIST.code, *treeCodes),
      listType(simple(Tree::class.java)).anyConverter.typeCodes(),
    )
  }

  @Test
  fun `a union that holds itself round-trips through the buffer`() {
    val tree = TypeDescriptor.Simple(Tree::class.java, false).anyConverter
    val value = Tree.Node(listOf(Tree.Leaf(1), Tree.Node(listOf(Tree.Leaf(2), Tree.Node(emptyList())))))

    val out = buffer.duplicateView()
    tree.writeToBuffer(out, value)
    val view = buffer.duplicateView()
    view.limit = out.position
    assertEquals(value, tree.readFromBuffer(view))
  }

  @Test
  fun `a value that is no variant cannot cross`() {
    val type = UnionRegistry.typeFor(Dimension::class.java)!!
    val error = assertFailsWith<IllegalArgumentException> { type.indexOf("not a dimension") }
    assertEquals("java.lang.String is not a variant of Dimension", error.message)
  }

  @Test
  fun `an index past the variants is refused`() {
    val error = assertFailsWith<IllegalArgumentException> {
      dimension.fromJni(UnionValue(7, null))
    }
    assertEquals("Dimension has no variant #7", error.message)
  }

  @Test
  fun `a union whose variants all cross as a string crosses as a plain string`() {
    val link = TypeDescriptor.Simple(Link::class.java, false).anyConverter
    assertContentEquals(intArrayOf(CppType.STRING.code), link.typeCodes())
    assertContentEquals(
      intArrayOf(CppType.STRING.nullableCode()),
      TypeDescriptor.Simple(Link::class.java, true).anyConverter.typeCodes(),
    )

    // No UnionValue and no index: the bare string, both ways.
    assertEquals("hello", link.toJni(Link.Text("hello")))
    assertEquals("https://expo.dev", link.toJni(Link.Address(URL("https://expo.dev"))))
  }

  @Test
  fun `a string the first variant refuses goes to the next one that crosses as a string`() {
    val link = TypeDescriptor.Simple(Link::class.java, false).anyConverter

    assertEquals(Link.Address(URL("https://expo.dev")), link.fromJni("https://expo.dev"))
    assertEquals(Link.Text("hello"), link.fromJni("hello"))

    // The buffer carries the string alone; the refused variant's read starts over.
    val out = buffer.duplicateView()
    link.writeToBuffer(out, Link.Text("hello"))
    val view = buffer.duplicateView()
    view.limit = out.position
    assertEquals("hello", view.getString())

    val again = buffer.duplicateView()
    again.limit = out.position
    assertEquals(Link.Text("hello"), link.readFromBuffer(again))
    assertEquals(out.position, again.position)
  }

  @Test
  fun `refs that carry different types fall back to each other`() {
    val refs = UnionRegistry.typeFor(Refs::class.java)!!
    assertEquals(1, refs.fallbackOf(0), "a ref the first variant refuses goes to the second")
    assertEquals(-1, refs.fallbackOf(1))
  }

  @Test
  fun `an Int-backed enum or an Int crosses as an Int, nullable when the union is`() {
    val priority = TypeDescriptor.Simple(Priority::class.java, false).anyConverter
    assertContentEquals(intArrayOf(CppType.INT.code), priority.typeCodes())
    assertContentEquals(
      intArrayOf(CppType.INT.nullableCode()),
      TypeDescriptor.Simple(Priority::class.java, true).anyConverter.typeCodes(),
    )

    assertEquals(Priority.Named(Level.HIGH), priority.fromJni(1))
    assertEquals(Priority.Custom(7), priority.fromJni(7))
    assertEquals(1, priority.toJni(Priority.Named(Level.HIGH)))
    assertEquals(7, priority.toJni(Priority.Custom(7)))
  }

  @Test
  fun `refs of one class cross as that shared object`() {
    val refs = TypeDescriptor.Simple(Refs::class.java, false).anyConverter
    val codes = refs.typeCodes()
    assertEquals(2, codes.size, "a shared object and its class id, no union around it")
    assertEquals(CppType.SHARED_OBJECT.code, codes[0])
  }
}
