package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Record
private data class Segment(
  val ends: Pair<Double, Double>,
  val label: Triple<String, Int, Boolean>? = null,
)

/** `Pair` and `Triple` cross as arrays of exactly two and three elements, each with its own type. */
@ExpoModule
private class Tuples : Module() {
  @JS
  fun swap(pair: Pair<Int, String>): Pair<String, Int> = Pair(pair.second, pair.first)

  @JS
  @BufferMode(Buffer.NO)
  fun swapDirect(pair: Pair<Int, String>): Pair<String, Int> = Pair(pair.second, pair.first)

  @JS
  fun rotate(triple: Triple<String, Double?, Boolean>): Triple<Double?, Boolean, String> =
    Triple(triple.second, triple.third, triple.first)

  @JS
  @BufferMode(Buffer.NO)
  fun rotateDirect(triple: Triple<String, Double?, Boolean>): Triple<Double?, Boolean, String> =
    Triple(triple.second, triple.third, triple.first)

  @JS
  fun maybe(pair: Pair<Int, Int>?): Pair<Int, Int>? = pair?.let { (a, b) -> Pair(a + b, a * b) }

  @JS
  fun zip(keys: List<String>, values: List<Int>): List<Pair<String, Int>> = keys.zip(values)

  @JS
  @BufferMode(Buffer.NO)
  fun zipDirect(keys: List<String>, values: List<Int>): List<Pair<String, Int>> = keys.zip(values)

  @JS
  fun unzip(pairs: List<Pair<String, Int>>): Pair<List<String>, List<Int>> = pairs.unzip()

  @JS
  fun hostAndPath(url: URL): Pair<String, URL> = Pair(url.host, url)

  @JS
  fun length(segment: Segment): Double = segment.ends.second - segment.ends.first

  @JS
  fun segment(from: Double, to: Double): Segment = Segment(Pair(from, to), Triple("len", (to - from).toInt(), true))

  @JS
  suspend fun later(pair: Pair<String, Boolean>): Triple<String, Boolean, Int> =
    Triple(pair.first, pair.second, pair.first.length)
}

class TupleTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withTuples(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Tuples())
      block(runtime)
    }
  }

  private fun HermesRuntime.json(script: String): String = evaluateAsString("JSON.stringify($script)")

  private fun HermesRuntime.errorOf(call: String): String = evaluateAsString(
    "(() => { try { $call; return 'no-throw'; } catch (e) { return e.message; } })()",
  )

  @Test
  fun `a pair crosses as a two-element array on the buffer and through JNI`() = withTuples { runtime ->
    assertEquals("""["one",1]""", runtime.json("expo.modules.Tuples.swap([1, 'one'])"))
    assertEquals("""["one",1]""", runtime.json("expo.modules.Tuples.swapDirect([1, 'one'])"))
    assertEquals("true", runtime.evaluateAsString("Array.isArray(expo.modules.Tuples.swap([1, 'one']))"))
  }

  @Test
  fun `a triple crosses as a three-element array, nullable elements included`() = withTuples { runtime ->
    assertEquals("""[1.5,true,"a"]""", runtime.json("expo.modules.Tuples.rotate(['a', 1.5, true])"))
    assertEquals("""[null,false,"b"]""", runtime.json("expo.modules.Tuples.rotate(['b', null, false])"))
    assertEquals("""[1.5,true,"a"]""", runtime.json("expo.modules.Tuples.rotateDirect(['a', 1.5, true])"))
    assertEquals("""[null,false,"b"]""", runtime.json("expo.modules.Tuples.rotateDirect(['b', null, false])"))
  }

  @Test
  fun `a nullable pair takes and returns null`() = withTuples { runtime ->
    assertEquals("null", runtime.json("expo.modules.Tuples.maybe(null)"))
    assertEquals("null", runtime.json("expo.modules.Tuples.maybe(undefined)"))
    assertEquals("[5,6]", runtime.json("expo.modules.Tuples.maybe([2, 3])"))
  }

  @Test
  fun `tuples nest inside lists and lists inside tuples`() = withTuples { runtime ->
    assertEquals(
      """[["a",1],["b",2]]""",
      runtime.json("expo.modules.Tuples.zip(['a', 'b'], [1, 2])"),
    )
    assertEquals(
      """[["a",1],["b",2]]""",
      runtime.json("expo.modules.Tuples.zipDirect(['a', 'b'], [1, 2])"),
    )
    assertEquals(
      """[["a","b"],[1,2]]""",
      runtime.json("expo.modules.Tuples.unzip([['a', 1], ['b', 2]])"),
    )
  }

  @Test
  fun `a converted element crosses as its bridge type`() = withTuples { runtime ->
    assertEquals(
      """["expo.dev","https://expo.dev/docs"]""",
      runtime.json("expo.modules.Tuples.hostAndPath('https://expo.dev/docs')"),
    )
  }

  @Test
  fun `a record field can be a tuple`() = withTuples { runtime ->
    assertEquals("2.5", runtime.evaluateAsString("expo.modules.Tuples.length({ ends: [1, 3.5] })"))
    assertEquals(
      """{"ends":[1,4],"label":["len",3,true]}""",
      runtime.json("expo.modules.Tuples.segment(1, 4)"),
    )
  }

  @Test
  fun `a suspend export takes and returns tuples`() = withTuples { runtime ->
    runtime.evaluate(
      "globalThis.out = null; expo.modules.Tuples.later(['four', true]).then((v) => { globalThis.out = JSON.stringify(v); });",
    )
    runtime.runEventLoop { !runtime.evaluate("globalThis.out === null").getBool() }
    assertEquals("""["four",true,4]""", runtime.evaluateAsString("globalThis.out"))
  }

  @Test
  fun `an array of the wrong length is rejected on both transports`() = withTuples { runtime ->
    for (call in listOf("swap([1])", "swapDirect([1, 'one', 2])", "rotate(['a', 1])", "rotateDirect([])")) {
      val message = runtime.errorOf("expo.modules.Tuples.$call")
      assertTrue("Expected an array of" in message, "$call: unexpected message: $message")
    }
  }

  @Test
  fun `a value that is not an array is rejected`() = withTuples { runtime ->
    for (call in listOf("swap({ 0: 1, 1: 'one' })", "swapDirect('ab')", "maybe(3)")) {
      val message = runtime.errorOf("expo.modules.Tuples.$call")
      assertTrue("an array" in message, "$call: unexpected message: $message")
    }
  }
}
