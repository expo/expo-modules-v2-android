package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Constant
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

@ExpoSharedObject
private class Ticket @JS constructor(private val text: String) : SharedObject() {
  private var reads = 0

  @JS
  @Constant
  val label: String
    get() {
      reads++
      return text.uppercase()
    }

  /** How many times Kotlin computed [label] for this instance. */
  @JS
  val labelReads: Int
    get() = reads

  @JS
  val size = 2

  @JS
  val notes = mutableListOf("x")

  @JS
  fun addNote(note: String) {
    notes.add(note)
  }
}

@ExpoModule(classes = [Ticket::class])
private class Settings : Module() {
  var answerReads = 0
  var liveReads = 0
  var flakyReads = 0

  @JS
  @Constant
  val answer: Int
    get() {
      answerReads++
      return 42
    }

  @JS
  @Constant
  val name: String
    get() = "expo"

  @JS
  val live: Int
    get() = ++liveReads

  /** Only holds a value, so it is a constant without the annotation. */
  @JS
  val version = 3

  /** Kotlin can still change what is inside it, so JavaScript reads it every time. */
  @JS
  val tags = mutableListOf("a")

  /** Fails on its first read, as a value that is not ready yet would. */
  @JS
  @Constant
  val flaky: Int
    get() = if (flakyReads++ == 0) throw IllegalStateException("not ready") else 7
}

class ConstantPropertyTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private fun withSettings(block: (HermesRuntime, Settings) -> Unit) {
    HermesRuntime().use { runtime ->
      val settings = Settings()
      runtime.moduleRegistry.register(settings)
      block(runtime, settings)
    }
  }

  @Test
  fun `a constant calls its getter once, however often JavaScript reads it`() = withSettings { runtime, settings ->
    assertEquals(
      "42,42,42,expo",
      runtime.evaluateAsString(
        "const s = expo.modules.Settings; [s.answer, s.answer, s.answer, s.name].join()",
      ),
    )
    assertEquals(1, settings.answerReads)
  }

  @Test
  fun `after its first read, a constant is a plain read-only value`() = withSettings { runtime, _ ->
    val describe =
      "(() => { const d = Object.getOwnPropertyDescriptor(expo.modules.Settings, 'answer'); " +
        "return [typeof d.get, 'value' in d, d.writable, d.enumerable].join(); })()"

    assertEquals("function,false,,true", runtime.evaluateAsString(describe))
    runtime.evaluate("expo.modules.Settings.answer")
    assertEquals("undefined,true,false,true", runtime.evaluateAsString(describe))
    assertEquals(
      "true",
      runtime.evaluateAsString(
        "(() => { 'use strict'; try { expo.modules.Settings.answer = 1; return false; } " +
          "catch (e) { return e instanceof TypeError; } })()",
      ),
    )
  }

  @Test
  fun `a val that only holds a value is a constant without the annotation`() = withSettings { runtime, _ ->
    val isAccessor = "typeof Object.getOwnPropertyDescriptor(expo.modules.Settings, 'version').get === 'function'"

    assertEquals("true", runtime.evaluateAsString("String($isAccessor)"))
    assertEquals("3", runtime.evaluateAsString("String(expo.modules.Settings.version)"))
    assertEquals("false", runtime.evaluateAsString("String($isAccessor)"))
  }

  @Test
  fun `a val whose contents can change is read every time`() = withSettings { runtime, settings ->
    assertEquals("a", runtime.evaluateAsString("expo.modules.Settings.tags.join()"))
    settings.tags.add("b")
    assertEquals("a,b", runtime.evaluateAsString("expo.modules.Settings.tags.join()"))
  }

  @Test
  fun `a property that is not a constant still calls its getter on every read`() = withSettings { runtime, settings ->
    assertEquals("1,2,3", runtime.evaluateAsString("const s = expo.modules.Settings; [s.live, s.live, s.live].join()"))
    assertEquals(3, settings.liveReads)
  }

  @Test
  fun `a shared object's constant calls its getter once per instance`() = withSettings { runtime, _ ->
    assertEquals(
      "A,A,B,1,1",
      runtime.evaluateAsString(
        "const B = expo.modules.Settings.Ticket; const a = new B('a'), b = new B('b'); " +
          "[a.label, a.label, b.label, a.labelReads, b.labelReads].join()",
      ),
    )
  }

  @Test
  fun `after its first read, a shared object's constant is the instance's own hidden value`() = withSettings { runtime, _ ->
    assertEquals(
      "false|A|undefined,true,false,false|false|",
      runtime.evaluateAsString(
        "(() => { const B = expo.modules.Settings.Ticket; const a = new B('a'), b = new B('b'); " +
          "const before = Object.prototype.hasOwnProperty.call(a, 'label'); const value = a.label; " +
          "const d = Object.getOwnPropertyDescriptor(a, 'label'); " +
          "return [before, value, [typeof d.get, 'value' in d, d.writable, d.enumerable].join(), " +
          "Object.prototype.hasOwnProperty.call(b, 'label'), Object.keys(a).join()].join('|'); })()",
      ),
    )
  }

  @Test
  fun `a shared object's val that only holds a value is a constant, and one that can change is not`() =
    withSettings { runtime, _ ->
      assertEquals(
        "2,true|x|x,y",
        runtime.evaluateAsString(
          "(() => { const a = new expo.modules.Settings.Ticket('a'); const size = a.size; " +
            "const cached = Object.prototype.hasOwnProperty.call(a, 'size'); const notes = a.notes.join(); " +
            "a.addNote('y'); return [[size, cached].join(), notes, a.notes.join()].join('|'); })()",
        ),
      )
    }

  @Test
  fun `a constant whose getter throws is read again next time`() = withSettings { runtime, settings ->
    assertEquals(
      "not ready|7|7",
      runtime.evaluateAsString(
        "(() => { const s = expo.modules.Settings; let first; " +
          "try { first = s.flaky; } catch (e) { first = e.message; } " +
          "return [first, s.flaky, s.flaky].join('|'); })()",
      ),
    )
    assertEquals(2, settings.flakyReads)
  }
}
