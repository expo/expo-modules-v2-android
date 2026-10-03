package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

@ExpoSharedObject
private class Accumulator @JS constructor(private var total: Int) : SharedObject() {
  @JS
  suspend fun addAsync(value: Int): Int = withContext(Dispatchers.Default) {
    synchronized(this@Accumulator) {
      total += value
      total
    }
  }

  @JS
  suspend fun addFromAsync(other: Accumulator): Int = addAsync(other.total)

  @JS
  suspend fun failAsync(): Int = withContext(Dispatchers.Default) { error("no total") }

  @JS
  fun total(): Int = total
}

/** Two classes whose exports name each other, so each one's registration meets the other's. */
@ExpoSharedObject
private class Statement @JS constructor() : SharedObject() {
  @JS
  fun databaseLabel(database: Database): String = database.label
}

@ExpoSharedObject
private class Database @JS constructor(val label: String) : SharedObject() {
  @JS
  fun prepare(statement: Statement): String = "prepared on $label: ${statement.databaseLabel(this)}"
}

@ExpoModule(classes = [Accumulator::class, Database::class, Statement::class])
private class Accumulators : Module()

private fun HermesRuntime.awaitSettled(script: String): String {
  runEventLoop { !evaluate("$script === null").getBool() }
  return evaluateAsString(script)
}

class SharedObjectAsyncTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withAccumulators(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Accumulators())
      block(runtime)
    }
  }

  @Test
  fun `a suspend method of a shared object resolves off the JS thread`() = withAccumulators { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "globalThis.acc = new expo.modules.Accumulators.Accumulator(1);" +
        "acc.addAsync(2).then((v) => { globalThis.out = v + ':' + acc.total(); });",
    )
    assertEquals("3:3", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a suspend method takes another shared object`() = withAccumulators { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "const a = new expo.modules.Accumulators.Accumulator(1);" +
        "const b = new expo.modules.Accumulators.Accumulator(5);" +
        "a.addFromAsync(b).then((v) => { globalThis.out = v; });",
    )
    assertEquals("6", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `a failing suspend method rejects`() = withAccumulators { runtime ->
    runtime.evaluate(
      "globalThis.out = null;" +
        "new expo.modules.Accumulators.Accumulator(0).failAsync()" +
        "  .catch((e) => { globalThis.out = e.code + '|' + e.message; });",
    )
    assertEquals("java.lang.IllegalStateException|no total", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `two classes whose exports name each other both register`() = withAccumulators { runtime ->
    assertEquals(
      "prepared on main: main",
      runtime.evaluateAsString(
        "const db = new expo.modules.Accumulators.Database('main');" +
          "db.prepare(new expo.modules.Accumulators.Statement())",
      ),
    )
  }
}
