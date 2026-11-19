package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.Enumerable
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.Tag
import io.github.expo.modules.v2.Union
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A number, the string "auto", or an object - one of each kind. */
@Union
sealed interface Dimension {
  @JvmInline
  value class Points(val value: Double) : Dimension

  @Tag("auto")
  data object Auto : Dimension

  @Record
  data class Percent(val percent: Double) : Dimension
}

/** A string or an integer: the union of two scalars. */
@Union
sealed interface Id {
  @JvmInline
  value class Text(val value: String) : Id

  @JvmInline
  value class Number(val value: Int) : Id
}

/** Records told apart by the fields they need. */
@Union
sealed interface Shape {
  @Record
  data class Circle(val radius: Double) : Shape

  @Record
  data class Rect(val width: Double, val height: Double = 1.0) : Shape
}

/** A sealed class rather than an interface, with variants of every remaining kind. */
@Union
sealed class Input {
  data class Flag(val value: Boolean) : Input()

  data class Values(val values: List<String>) : Input()

  data class Bytes(val bytes: ByteArray) : Input()

  data object None : Input()
}

/** Two variants that both take a string: the first declared that takes it wins. */
@Union
sealed interface Link {
  @JvmInline
  value class Address(val url: URL) : Link

  @JvmInline
  value class Text(val text: String) : Link
}

/** Two variants that both take a number: a whole number is an Int. */
@Union
sealed interface Amount {
  @JvmInline
  value class Whole(val value: Int) : Amount

  @JvmInline
  value class Fraction(val value: Double) : Amount
}

/** Records whose fields overlap: an object goes to the first whose every required field it has. */
@Union
sealed interface Figure {
  @Record
  data class Circle(val x: Double, val y: Double, val radius: Double) : Figure

  @Record
  data class Point(val x: Double, val y: Double, val label: String? = null) : Figure
}

@ExpoSharedObject
private class LabelRef(label: StringBuilder) : SharedRef<StringBuilder>(label)

@ExpoSharedObject
private class CounterRef(counter: AtomicInteger) : SharedRef<AtomicInteger>(counter)

/** Refs of one class that carry different types: only Kotlin can see which a ref is. */
@Union
sealed interface RefHandle {
  @JvmInline
  value class Label(val ref: SharedRef<StringBuilder>) : RefHandle

  @JvmInline
  value class Counter(val ref: SharedRef<AtomicInteger>) : RefHandle
}

/** Lists told apart by what they hold: an empty one goes to the first. */
@Union
sealed interface Items {
  @JvmInline
  value class Counts(val values: List<Int>) : Items

  @JvmInline
  value class Labels(val values: List<SharedRef<StringBuilder>>) : Items

  @JvmInline
  value class Words(val values: List<String>) : Items
}

/** Maps told apart by what they hold. */
@Union
sealed interface Tally {
  @JvmInline
  value class Totals(val totals: Map<String, Int>) : Tally

  @JvmInline
  value class Names(val names: Map<String, String>) : Tally
}

/** A union that holds itself: a number, or an array of numbers and arrays, nested to any depth. */
@Union
sealed interface Tree {
  @JvmInline
  value class Leaf(val value: Int) : Tree

  @JvmInline
  value class Node(val children: List<Tree>) : Tree
}

private fun Tree.sum(): Int = when (this) {
  is Tree.Leaf -> value
  is Tree.Node -> children.sumOf { it.sum() }
}

private fun Tree.mirror(): Tree = when (this) {
  is Tree.Leaf -> this
  is Tree.Node -> Tree.Node(children.reversed().map { it.mirror() })
}

/** Binary data or a record: an object that is no ArrayBuffer goes to the record. */
@Union
sealed interface Blob {
  @JvmInline
  value class Bytes(val bytes: ByteArray) : Blob

  @Record
  data class Size(val width: Double, val height: Double) : Blob
}

enum class Tier(val value: Int) : Enumerable { LOW(0), HIGH(1) }

/** An Int-backed enum or any other Int: both cross as an Int, and Kotlin picks the variant. */
@Union
sealed interface Rank {
  @JvmInline
  value class Named(val level: Tier) : Rank

  @JvmInline
  value class Custom(val value: Int) : Rank
}

@Record
data class Layout(
  val width: Dimension,
  val height: Dimension? = null,
  val shapes: List<Shape> = emptyList(),
)

@ExpoModule
private class Unions : Module() {
  @JS
  fun resolve(dimension: Dimension): String = when (dimension) {
    is Dimension.Points -> "points:${dimension.value}"
    Dimension.Auto -> "auto"
    is Dimension.Percent -> "percent:${dimension.percent}"
  }

  @JS
  @BufferMode(Buffer.NO)
  fun resolveOverJni(dimension: Dimension): String = resolve(dimension)

  @JS
  fun dimension(kind: String): Dimension = when (kind) {
    "points" -> Dimension.Points(12.5)
    "auto" -> Dimension.Auto
    else -> Dimension.Percent(0.5)
  }

  @JS
  @BufferMode(Buffer.NO, returns = Buffer.NO)
  fun dimensionOverJni(kind: String): Dimension = dimension(kind)

  @JS
  fun maybe(dimension: Dimension?): String = dimension?.let(::resolve) ?: "none"

  @JS
  fun echo(dimension: Dimension?): Dimension? = dimension

  @JS
  fun find(id: Id): String = when (id) {
    is Id.Text -> "text:${id.value}"
    is Id.Number -> "number:${id.value}"
  }

  @JS
  fun nextId(id: Id): Id = when (id) {
    is Id.Text -> Id.Text(id.value + "!")
    is Id.Number -> Id.Number(id.value + 1)
  }

  @JS
  fun label(text: String): SharedRef<StringBuilder> = LabelRef(StringBuilder(text))

  @JS
  fun counter(value: Int): SharedRef<AtomicInteger> = CounterRef(AtomicInteger(value))

  @JS
  fun read(handle: RefHandle): String = when (handle) {
    is RefHandle.Label -> "label:${handle.ref.ref}"
    is RefHandle.Counter -> "counter:${handle.ref.ref.get()}"
  }

  @JS
  fun items(items: Items): String = when (items) {
    is Items.Counts -> "counts:${items.values.sum()}"
    is Items.Labels -> "labels:${items.values.joinToString { it.ref }}"
    is Items.Words -> "words:${items.values.joinToString()}"
  }

  @JS
  fun tally(tally: Tally): String = when (tally) {
    is Tally.Totals -> "totals:${tally.totals.values.sum()}"
    is Tally.Names -> "names:${tally.names.values.sorted().joinToString()}"
  }

  @JS
  @BufferMode(Buffer.NO)
  fun tallyOverJni(tally: Tally): String = tally(tally)

  @JS
  fun counterHandle(value: Int): RefHandle = RefHandle.Counter(CounterRef(AtomicInteger(value)))

  @JS
  fun area(shape: Shape): Double = when (shape) {
    is Shape.Circle -> 3.0 * shape.radius * shape.radius
    is Shape.Rect -> shape.width * shape.height
  }

  @JS
  @BufferMode(Buffer.NO)
  fun areaOverJni(shape: Shape): Double = area(shape)

  @JS
  fun square(side: Double): Shape = Shape.Rect(side, side)

  @JS
  @BufferMode(returns = Buffer.NO)
  fun circleOverJni(radius: Double): Shape = Shape.Circle(radius)

  @JS
  fun input(input: Input): String = when (input) {
    is Input.Flag -> "flag:${input.value}"
    is Input.Values -> "values:${input.values.joinToString()}"
    is Input.Bytes -> "bytes:${input.bytes.size}"
    Input.None -> "none"
  }

  @JS
  fun describeLayout(layout: Layout): String =
    "${resolve(layout.width)}/${layout.height?.let(::resolve)}/${layout.shapes.map(::area)}"

  @JS
  fun layout(): Layout = Layout(Dimension.Auto, Dimension.Points(3.0), listOf(Shape.Circle(1.0)))

  @JS
  fun widths(dimensions: List<Dimension>): List<Dimension> = dimensions.reversed()

  @JS
  suspend fun later(dimension: Dimension): Dimension = dimension

  @JS
  fun link(link: Link): String = when (link) {
    is Link.Address -> "address:${link.url.host}"
    is Link.Text -> "text:${link.text}"
  }

  @JS
  @BufferMode(Buffer.NO)
  fun linkOverJni(link: Link): String = link(link)

  @JS
  fun amount(amount: Amount): String = when (amount) {
    is Amount.Whole -> "whole:${amount.value}"
    is Amount.Fraction -> "fraction:${amount.value}"
  }

  @JS
  fun figure(figure: Figure): String = when (figure) {
    is Figure.Circle -> "circle:${figure.radius}"
    is Figure.Point -> "point:${figure.x},${figure.y}"
  }

  @JS
  @BufferMode(Buffer.NO)
  fun figureOverJni(figure: Figure): String = figure(figure)

  @JS
  fun priority(priority: Rank?): String = when (priority) {
    is Rank.Named -> "named:${priority.level}"
    is Rank.Custom -> "custom:${priority.value}"
    null -> "none"
  }

  @JS
  @BufferMode(Buffer.NO)
  fun priorityOverJni(priority: Rank?): String = priority(priority)

  @JS
  fun strict(priority: Rank): String = priority(priority)

  @JS
  fun high(): Rank = Rank.Named(Tier.HIGH)

  @JS
  fun custom(): Rank = Rank.Custom(42)

  @JS
  fun blob(blob: Blob): String = when (blob) {
    is Blob.Bytes -> "bytes:${blob.bytes.size}"
    is Blob.Size -> "size:${blob.width}x${blob.height}"
  }

  @JS
  @BufferMode(Buffer.NO)
  fun blobOverJni(blob: Blob): String = blob(blob)

  @JS
  fun sum(tree: Tree): Int = tree.sum()

  @JS
  @BufferMode(Buffer.NO)
  fun sumOverJni(tree: Tree): Int = tree.sum()

  @JS
  fun mirror(tree: Tree): Tree = tree.mirror()

  @JS
  @BufferMode(Buffer.NO, returns = Buffer.NO)
  fun mirrorOverJni(tree: Tree): Tree = tree.mirror()

  @JS
  fun sumAll(trees: List<Tree>): Int = trees.sumOf { it.sum() }
}

private fun HermesRuntime.awaitSettled(script: String): String {
  runEventLoop { !evaluate("$script === null").getBool() }
  return evaluateAsString(script)
}

private fun HermesRuntime.errorOf(script: String): String =
  evaluateAsString("(() => { try { $script; return 'no-throw'; } catch (e) { return e.message; } })()")

class UnionTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withUnions(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Unions())
      block(runtime)
    }
  }

  @Test
  fun `each kind of JavaScript value picks its variant`() = withUnions { runtime ->
    assertEquals("points:12.0", runtime.evaluateAsString("expo.modules.Unions.resolve(12)"))
    assertEquals("auto", runtime.evaluateAsString("expo.modules.Unions.resolve('auto')"))
    assertEquals("percent:0.25", runtime.evaluateAsString("expo.modules.Unions.resolve({ percent: 0.25 })"))
  }

  @Test
  fun `a variant is picked the same way over JNI`() = withUnions { runtime ->
    assertEquals("points:12.0", runtime.evaluateAsString("expo.modules.Unions.resolveOverJni(12)"))
    assertEquals("auto", runtime.evaluateAsString("expo.modules.Unions.resolveOverJni('auto')"))
    assertEquals(
      "percent:0.25",
      runtime.evaluateAsString("expo.modules.Unions.resolveOverJni({ percent: 0.25 })"),
    )
  }

  @Test
  fun `a returned variant crosses as its value`() = withUnions { runtime ->
    for (function in listOf("dimension", "dimensionOverJni")) {
      assertEquals("12.5", runtime.evaluateAsString("expo.modules.Unions.$function('points')"))
      assertEquals("auto", runtime.evaluateAsString("expo.modules.Unions.$function('auto')"))
      assertEquals(
        """{"percent":0.5}""",
        runtime.evaluateAsString("JSON.stringify(expo.modules.Unions.$function('percent'))"),
      )
    }
  }

  @Test
  fun `a ref goes to the variant that carries its type`() = withUnions { runtime ->
    assertEquals("label:hi", runtime.evaluateAsString("expo.modules.Unions.read(expo.modules.Unions.label('hi'))"))
    assertEquals("counter:7", runtime.evaluateAsString("expo.modules.Unions.read(expo.modules.Unions.counter(7))"))
    assertEquals(
      "counter:3",
      runtime.evaluateAsString("expo.modules.Unions.read(expo.modules.Unions.counterHandle(3))"),
    )
  }

  @Test
  fun `lists go to the variant whose elements they hold`() = withUnions { runtime ->
    assertEquals("counts:3", runtime.evaluateAsString("expo.modules.Unions.items([1, 2])"))
    // Inside a list a number is not checked against its type: List<Int> truncates it, like any Int.
    assertEquals("counts:1", runtime.evaluateAsString("expo.modules.Unions.items([1, 0.5])"))
    assertEquals("words:a, b", runtime.evaluateAsString("expo.modules.Unions.items(['a', 'b'])"))
    assertEquals(
      "labels:x, y",
      runtime.evaluateAsString(
        "expo.modules.Unions.items([expo.modules.Unions.label('x'), expo.modules.Unions.label('y')])",
      ),
    )
    assertEquals("counts:0", runtime.evaluateAsString("expo.modules.Unions.items([])"))
  }

  @Test
  fun `maps go to the variant whose values they hold`() = withUnions { runtime ->
    for (function in listOf("tally", "tallyOverJni")) {
      assertEquals("totals:3", runtime.evaluateAsString("expo.modules.Unions.$function({ a: 1, b: 2 })"))
      assertEquals("names:x, y", runtime.evaluateAsString("expo.modules.Unions.$function({ a: 'x', b: 'y' })"))
    }
  }

  @Test
  fun `a nullable union takes and returns null`() = withUnions { runtime ->
    assertEquals("none", runtime.evaluateAsString("expo.modules.Unions.maybe(null)"))
    assertEquals("none", runtime.evaluateAsString("expo.modules.Unions.maybe(undefined)"))
    assertEquals("auto", runtime.evaluateAsString("expo.modules.Unions.maybe('auto')"))
    assertEquals("null", runtime.evaluateAsString("String(expo.modules.Unions.echo(null))"))
    assertEquals("7", runtime.evaluateAsString("expo.modules.Unions.echo(7)"))
  }

  @Test
  fun `a string or an integer`() = withUnions { runtime ->
    assertEquals("text:alice", runtime.evaluateAsString("expo.modules.Unions.find('alice')"))
    assertEquals("number:42", runtime.evaluateAsString("expo.modules.Unions.find(42)"))
    assertEquals("43", runtime.evaluateAsString("expo.modules.Unions.nextId(42)"))
    assertEquals("number", runtime.evaluateAsString("typeof expo.modules.Unions.nextId(42)"))
    assertEquals("a!", runtime.evaluateAsString("expo.modules.Unions.nextId('a')"))
  }

  @Test
  fun `the fields of an object pick the record`() = withUnions { runtime ->
    assertEquals("12", runtime.evaluateAsString("expo.modules.Unions.area({ radius: 2 })"))
    assertEquals("6", runtime.evaluateAsString("expo.modules.Unions.area({ width: 2, height: 3 })"))
    assertEquals("2", runtime.evaluateAsString("expo.modules.Unions.area({ width: 2 })"))
    assertEquals("6", runtime.evaluateAsString("expo.modules.Unions.areaOverJni({ width: 2, height: 3 })"))
  }

  @Test
  fun `a returned record variant crosses as a plain object`() = withUnions { runtime ->
    assertEquals(
      """{"width":2,"height":2}""",
      runtime.evaluateAsString("JSON.stringify(expo.modules.Unions.square(2))"),
    )
    assertEquals(
      """{"radius":1.5}""",
      runtime.evaluateAsString("JSON.stringify(expo.modules.Unions.circleOverJni(1.5))"),
    )
  }

  @Test
  fun `a sealed class with boolean, array, binary and object variants`() = withUnions { runtime ->
    assertEquals("flag:true", runtime.evaluateAsString("expo.modules.Unions.input(true)"))
    assertEquals("values:a, b", runtime.evaluateAsString("expo.modules.Unions.input(['a', 'b'])"))
    assertEquals("bytes:3", runtime.evaluateAsString("expo.modules.Unions.input(new Uint8Array([1, 2, 3]))"))
    assertEquals("bytes:4", runtime.evaluateAsString("expo.modules.Unions.input(new ArrayBuffer(4))"))
    assertEquals("none", runtime.evaluateAsString("expo.modules.Unions.input('None')"))
  }

  @Test
  fun `a union can be a record field and a list element`() = withUnions { runtime ->
    assertEquals(
      "auto/points:4.0/[12.0, 2.0]",
      runtime.evaluateAsString(
        "expo.modules.Unions.describeLayout({ width: 'auto', height: 4, shapes: [{ radius: 2 }, { width: 2 }] })",
      ),
    )
    assertEquals(
      "percent:0.5/null/[]",
      runtime.evaluateAsString("expo.modules.Unions.describeLayout({ width: { percent: 0.5 } })"),
    )
    assertEquals(
      """{"width":"auto","height":3,"shapes":[{"radius":1}]}""",
      runtime.evaluateAsString("JSON.stringify(expo.modules.Unions.layout())"),
    )
    assertEquals(
      """[{"percent":1},"auto",5]""",
      runtime.evaluateAsString("JSON.stringify(expo.modules.Unions.widths([5, 'auto', { percent: 1 }]))"),
    )
  }

  @Test
  fun `a suspend export takes and returns a union`() = withUnions { runtime ->
    runtime.evaluate("globalThis.out = null; expo.modules.Unions.later('auto').then((v) => { globalThis.out = v; });")
    assertEquals("auto", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a value no variant takes throws an error that lists them`() = withUnions { runtime ->
    val message = runtime.errorOf("expo.modules.Unions.resolve(true)")
    assertEquals("Expected Dimension (number | 'auto' | Percent), got a boolean", message)

    val overJni = runtime.errorOf("expo.modules.Unions.resolveOverJni([])")
    assertEquals("Expected Dimension (number | 'auto' | Percent), got an object", overJni)
  }

  @Test
  fun `a string that is not the tag of an object variant is not taken`() = withUnions { runtime ->
    val message = runtime.errorOf("expo.modules.Unions.resolve('fill')")
    assertTrue("Dimension" in message && "a string" in message, "unexpected message: $message")
  }

  @Test
  fun `a string the first variant refuses goes to the next one`() = withUnions { runtime ->
    for (function in listOf("link", "linkOverJni")) {
      assertEquals("address:expo.dev", runtime.evaluateAsString("expo.modules.Unions.$function('https://expo.dev/x')"))
      assertEquals("text:hello", runtime.evaluateAsString("expo.modules.Unions.$function('hello')"))
    }
  }

  @Test
  fun `a whole number is an Int and any other number a Double`() = withUnions { runtime ->
    assertEquals("whole:3", runtime.evaluateAsString("expo.modules.Unions.amount(3)"))
    assertEquals("fraction:2.5", runtime.evaluateAsString("expo.modules.Unions.amount(2.5)"))
    assertEquals("fraction:3.0E9", runtime.evaluateAsString("expo.modules.Unions.amount(3e9)"))
  }

  @Test
  fun `an object goes to the first record whose fields it has`() = withUnions { runtime ->
    for (function in listOf("figure", "figureOverJni")) {
      assertEquals("circle:2.0", runtime.evaluateAsString("expo.modules.Unions.$function({ x: 1, y: 1, radius: 2 })"))
      assertEquals("point:1.0,2.0", runtime.evaluateAsString("expo.modules.Unions.$function({ x: 1, y: 2 })"))
    }
    val message = runtime.errorOf("expo.modules.Unions.figure({ x: 1 })")
    assertTrue("'y'" in message, "unexpected message: $message")
  }

  @Test
  fun `a null for a non-nullable union throws`() = withUnions { runtime ->
    val message = runtime.errorOf("expo.modules.Unions.resolve(null)")
    assertTrue("Dimension" in message, "unexpected message: $message")
  }

  @Test
  fun `a union that holds itself crosses at any depth`() = withUnions { runtime ->
    for (function in listOf("sum", "sumOverJni")) {
      assertEquals("4", runtime.evaluateAsString("String(expo.modules.Unions.$function(4))"))
      assertEquals("0", runtime.evaluateAsString("String(expo.modules.Unions.$function([]))"))
      assertEquals(
        "10",
        runtime.evaluateAsString("String(expo.modules.Unions.$function([1, [2, [3, [4]]], []]))"),
      )
    }
    assertEquals("6", runtime.evaluateAsString("String(expo.modules.Unions.sumAll([1, [2], [[3]]]))"))
  }

  @Test
  fun `a returned union that holds itself crosses as nested arrays`() = withUnions { runtime ->
    for (function in listOf("mirror", "mirrorOverJni")) {
      assertEquals(
        "[[[4],3],2,1]",
        runtime.evaluateAsString("JSON.stringify(expo.modules.Unions.$function([1, 2, [3, [4]]]))"),
      )
    }
  }

  @Test
  fun `a value deep in a union that holds itself is checked`() = withUnions { runtime ->
    for (function in listOf("sum", "sumOverJni")) {
      val message = runtime.errorOf("expo.modules.Unions.$function([1, [2, 'three']])")
      assertTrue("Tree" in message, "unexpected message: $message")
    }
  }

  @Test
  fun `an object that is no ArrayBuffer goes past a ByteArray variant`() = withUnions { runtime ->
    for (function in listOf("blob", "blobOverJni")) {
      assertEquals("bytes:3", runtime.evaluateAsString("expo.modules.Unions.$function(new Uint8Array([1, 2, 3]))"))
      assertEquals("size:2.0x3.0", runtime.evaluateAsString("expo.modules.Unions.$function({width: 2, height: 3})"))
    }
  }

  @Test
  fun `an Int-backed enum or an Int crosses as an Int`() = withUnions { runtime ->
    for (function in listOf("priority", "priorityOverJni", "strict")) {
      assertEquals("named:HIGH", runtime.evaluateAsString("expo.modules.Unions.$function(1)"))
      assertEquals("custom:7", runtime.evaluateAsString("expo.modules.Unions.$function(7)"))
    }
    assertEquals("none", runtime.evaluateAsString("expo.modules.Unions.priority(null)"))
    assertEquals("none", runtime.evaluateAsString("expo.modules.Unions.priorityOverJni(null)"))
    assertEquals("1", runtime.evaluateAsString("String(expo.modules.Unions.high())"))
    assertEquals("42", runtime.evaluateAsString("String(expo.modules.Unions.custom())"))
  }
}
