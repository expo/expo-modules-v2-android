package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Maps whose keys JavaScript gets from the per-runtime cache of property names, and past it. */
@ExpoModule(name = "MapKeys")
private class MapKeysModule : Module() {
  /** [count] distinct keys, more than the cache holds once [count] is large enough. */
  @JS
  fun many(prefix: String, count: Int): Map<String, Int> =
    (0 until count).associate { "$prefix$it" to it }

  /** Keys longer than the cache takes, next to short ones. */
  @JS
  fun long(): Map<String, Int> = mapOf("k".repeat(65) to 65, "k".repeat(64) to 64, "k" to 1)

  /** The same keys on every level, so names are reused while nested maps add keys of their own. */
  @JS
  @BufferMode(returns = Buffer.YES)
  fun nested(depth: Int): Map<String, Any?> {
    var value: Map<String, Any?> = mapOf("id" to 0, "name" to "leaf")
    for (level in 1..depth) {
      value = mapOf("id" to level, "child" to value, "extra$level" to level)
    }
    return value
  }

  /** A map of [count] keys under [key], so decoding it evicts every name, the one of [key] too. */
  @JS
  @BufferMode(returns = Buffer.YES)
  fun wrapped(key: String, count: Int): Map<String, Any?> =
    mapOf(key to many(key, count), "after" to count)

  /** Non-ASCII keys, which are not cached. */
  @JS
  fun utf16(): Map<String, Int> = mapOf("zażółć" to 1, "ascii" to 2)
}

class MapKeysTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withModule(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(MapKeysModule())
      block(runtime)
    }
  }

  private val checkMany =
    "(prefix, count) => {" +
      "  const m = expo.modules.MapKeys.many(prefix, count);" +
      "  const keys = Object.keys(m);" +
      "  if (keys.length !== count) return 'count ' + keys.length;" +
      "  for (let i = 0; i < count; i++) if (m[prefix + i] !== i) return 'at ' + i;" +
      "  return 'ok';" +
      "}"

  @Test
  fun `maps with more distinct keys than the cache holds keep every key`() = withModule { runtime ->
    runtime.evaluate("globalThis.checkMany = $checkMany")
    // The first call fills the cache, and the keys past it take the place of others.
    repeat(3) { assertEquals("ok", runtime.evaluateAsString("checkMany('a', 1500)")) }
    // Keys that stop coming back make room for new ones: cached and uncached keys mix in one map.
    repeat(3) { assertEquals("ok", runtime.evaluateAsString("checkMany('b', 2000)")) }
    assertEquals("ok", runtime.evaluateAsString("checkMany('a', 10)"))
  }

  @Test
  fun `a nested map that evicts every name leaves the key of its parent intact`() =
    withModule { runtime ->
      val check =
        "(() => { const m = expo.modules.MapKeys.wrapped('outer', 1500);" +
          "  return [Object.keys(m).join('+'), Object.keys(m.outer).length, m.outer.outer1499]" +
          "    .join(','); })()"
      // The inner map has more keys than the cache holds, so decoding it evicts names the cache
      // handed out before. A decoder that looked `outer` up before its value would hold a freed
      // name here, which only a sanitizer reports reliably.
      repeat(3) { assertEquals("outer+after,1500,1499", runtime.evaluateAsString(check)) }
    }

  @Test
  fun `long keys and non-ASCII keys cross as they are`() = withModule { runtime ->
    assertEquals(
      "65,64,1",
      runtime.evaluateAsString(
        "(() => { const m = expo.modules.MapKeys.long();" +
          "  return [m['k'.repeat(65)], m['k'.repeat(64)], m.k].join(','); })()",
      ),
    )
    assertEquals(
      "1,2",
      runtime.evaluateAsString(
        "(() => { const m = expo.modules.MapKeys.utf16(); return [m['zażółć'], m.ascii].join(','); })()",
      ),
    )
  }

  @Test
  fun `nested maps that share keys decode on every level`() = withModule { runtime ->
    assertEquals(
      "3,2,1,0,leaf,extra3",
      runtime.evaluateAsString(
        "(() => {" +
          "  let m = expo.modules.MapKeys.nested(3);" +
          "  const ids = [];" +
          "  const first = Object.keys(m).filter((k) => k.startsWith('extra'))[0];" +
          "  while (m.child) { ids.push(m.id); m = m.child; }" +
          "  ids.push(m.id, m.name, first);" +
          "  return ids.join(',');" +
          "})()",
      ),
    )
  }

  @Test
  fun `two runtimes on one thread each get their own names`() {
    HermesRuntime().use { first ->
      first.moduleRegistry.register(MapKeysModule())
      HermesRuntime().use { second ->
        second.moduleRegistry.register(MapKeysModule())
        first.evaluate("globalThis.checkMany = $checkMany")
        second.evaluate("globalThis.checkMany = $checkMany")
        repeat(3) {
          assertEquals("ok", first.evaluateAsString("checkMany('k', 20)"))
          assertEquals("ok", second.evaluateAsString("checkMany('k', 20)"))
        }
      }
      // The second runtime is gone; the first one still decodes with names of its own.
      assertEquals("ok", first.evaluateAsString("checkMany('k', 20)"))
    }
  }
}
