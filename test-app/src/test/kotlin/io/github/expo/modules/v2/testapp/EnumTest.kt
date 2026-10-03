package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** No constructor property, so JavaScript sees each entry by its name. */
private enum class Size { SMALL, LARGE }

/** One `String` constructor property, so JavaScript sees each entry by that value. */
private enum class Style(val value: String) {
  LIGHT("light"),
  HEAVY("heavy"),
}

/** One `Int` constructor property, so JavaScript sees each entry as that number. */
private enum class Priority(val value: Int) {
  LOW(0),
  NORMAL(5),
  HIGH(10),
}

@Record
private data class Task(
  val priority: Priority = Priority.NORMAL,
  val fallback: Priority? = null,
)

@Record
private data class Options(
  val style: Style = Style.LIGHT,
  val size: Size? = null,
)

@ExpoModule
private class Enums : Module() {
  @JS
  fun echoSize(size: Size): Size = size

  @JS
  fun nameOf(style: Style): String = style.name

  @JS
  fun styleNamed(name: String): Style = Style.valueOf(name)

  @JS
  fun maybe(style: Style?): String = style?.value ?: "none"

  @JS
  fun describe(options: Options): String = "${options.style.value}/${options.size}"

  @JS
  fun options(style: Style): Options = Options(style, Size.LARGE)

  @JS
  fun reversed(styles: List<Style>): List<Style> = styles.reversed()

  @JS
  suspend fun later(style: Style): Style = style

  @JS
  fun nameOfPriority(priority: Priority): String = priority.name

  @JS
  fun priorityNamed(name: String): Priority = Priority.valueOf(name)

  @JS
  fun maybePriority(priority: Priority?): String = priority?.name ?: "none"

  @JS
  fun nextPriority(priority: Priority?): Priority? = priority?.let { Priority.entries.getOrNull(it.ordinal + 1) }

  @JS
  fun describeTask(task: Task): String = "${task.priority.value}/${task.fallback?.value}"

  @JS
  fun task(priority: Priority): Task = Task(priority, Priority.LOW)

  @JS
  fun reversedPriorities(priorities: List<Priority>): List<Priority> = priorities.reversed()

  @JS
  suspend fun laterPriority(priority: Priority): Priority = priority
}

private fun HermesRuntime.awaitSettled(script: String): String {
  runEventLoop { !evaluate("$script === null").getBool() }
  return evaluateAsString(script)
}

class EnumTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withEnums(block: (HermesRuntime) -> Unit) {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(Enums())
      block(runtime)
    }
  }

  @Test
  fun `an enum without a property crosses as its name`() = withEnums { runtime ->
    assertEquals("SMALL", runtime.evaluateAsString("expo.modules.Enums.echoSize('SMALL')"))
  }

  @Test
  fun `an enum with a String property crosses as that value`() = withEnums { runtime ->
    assertEquals("HEAVY", runtime.evaluateAsString("expo.modules.Enums.nameOf('heavy')"))
    assertEquals("light", runtime.evaluateAsString("expo.modules.Enums.styleNamed('LIGHT')"))
  }

  @Test
  fun `a nullable enum takes null`() = withEnums { runtime ->
    assertEquals("none", runtime.evaluateAsString("expo.modules.Enums.maybe(null)"))
    assertEquals("heavy", runtime.evaluateAsString("expo.modules.Enums.maybe('heavy')"))
  }

  @Test
  fun `a record field can be an enum, with a default`() = withEnums { runtime ->
    assertEquals("light/null", runtime.evaluateAsString("expo.modules.Enums.describe({})"))
    assertEquals(
      "heavy/LARGE",
      runtime.evaluateAsString("expo.modules.Enums.describe({ style: 'heavy', size: 'LARGE' })"),
    )
    assertEquals(
      """{"style":"heavy","size":"LARGE"}""",
      runtime.evaluateAsString("JSON.stringify(expo.modules.Enums.options('heavy'))"),
    )
  }

  @Test
  fun `a list of enums crosses element by element`() = withEnums { runtime ->
    assertEquals(
      """["heavy","light"]""",
      runtime.evaluateAsString("JSON.stringify(expo.modules.Enums.reversed(['light', 'heavy']))"),
    )
  }

  @Test
  fun `a suspend export takes and returns an enum`() = withEnums { runtime ->
    runtime.evaluate("globalThis.out = null; expo.modules.Enums.later('heavy').then((v) => { globalThis.out = v; });")
    assertEquals("heavy", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `an unknown value throws an error that lists the accepted ones`() = withEnums { runtime ->
    val message = runtime.evaluateAsString(
      "(() => { try { expo.modules.Enums.nameOf('medium'); return 'no-throw'; } catch (e) { return e.message; } })()",
    )
    assertTrue("'medium'" in message, "unexpected message: $message")
    assertTrue("'light', 'heavy'" in message, "unexpected message: $message")
  }

  @Test
  fun `an enum with an Int property crosses as that number`() = withEnums { runtime ->
    assertEquals("HIGH", runtime.evaluateAsString("expo.modules.Enums.nameOfPriority(10)"))
    assertEquals("5", runtime.evaluateAsString("expo.modules.Enums.priorityNamed('NORMAL')"))
    assertEquals("number", runtime.evaluateAsString("typeof expo.modules.Enums.priorityNamed('LOW')"))
  }

  @Test
  fun `a nullable Int enum takes and returns null`() = withEnums { runtime ->
    assertEquals("none", runtime.evaluateAsString("expo.modules.Enums.maybePriority(null)"))
    assertEquals("LOW", runtime.evaluateAsString("expo.modules.Enums.maybePriority(0)"))
    assertEquals("10", runtime.evaluateAsString("expo.modules.Enums.nextPriority(5)"))
    assertEquals("null", runtime.evaluateAsString("expo.modules.Enums.nextPriority(10)"))
  }

  @Test
  fun `a record field can be an Int enum, with a default`() = withEnums { runtime ->
    assertEquals("5/null", runtime.evaluateAsString("expo.modules.Enums.describeTask({})"))
    assertEquals(
      "10/0",
      runtime.evaluateAsString("expo.modules.Enums.describeTask({ priority: 10, fallback: 0 })"),
    )
    assertEquals(
      """{"priority":10,"fallback":0}""",
      runtime.evaluateAsString("JSON.stringify(expo.modules.Enums.task(10))"),
    )
  }

  @Test
  fun `a list of Int enums crosses element by element`() = withEnums { runtime ->
    assertEquals(
      "[10,0,5]",
      runtime.evaluateAsString("JSON.stringify(expo.modules.Enums.reversedPriorities([5, 0, 10]))"),
    )
  }

  @Test
  fun `a suspend export takes and returns an Int enum`() = withEnums { runtime ->
    runtime.evaluate(
      "globalThis.out = null; expo.modules.Enums.laterPriority(5).then((v) => { globalThis.out = typeof v + ':' + v; });",
    )
    assertEquals("number:5", runtime.awaitSettled("globalThis.out"))
  }

  @Test
  fun `an unknown number throws an error that lists the accepted ones`() = withEnums { runtime ->
    val message = runtime.evaluateAsString(
      "(() => { try { expo.modules.Enums.nameOfPriority(7); return 'no-throw'; } catch (e) { return e.message; } })()",
    )
    assertTrue("'7'" in message, "unexpected message: $message")
    assertTrue("0, 5, 10" in message, "unexpected message: $message")
  }
}
