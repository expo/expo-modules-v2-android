package io.github.expo.modules.v2.sharedobjects

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.modules.ModuleBuilder
import io.github.expo.modules.v2.types.CppType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Pins what the plugin generates for a `@ExpoSharedObject` class, and for an `@ExpoModule` that
 * passes one around.
 *
 * A shared object is a reference, so the two rules under test are: it never rides the binary buffer,
 * and its codes carry the class id the native encoder type-checks against.
 */
class GeneratedSharedObjectTest {
  @ExpoSharedObject
  private class Player : SharedObject() {
    @JS fun play(): Int = 1

    @JS var volume: Double = 1.0

    @JS fun rename(name: String): String = name

    @Event val onStateChange = event<String>()
  }

  @ExpoSharedObject(name = "Renamed")
  private class Speaker : SharedObject() {
    @JS fun mute() = Unit
  }

  @ExpoModule
  private class Players : Module() {
    @JS fun create(): Player = Player()

    @JS fun volumeOf(player: Player): Double = player.volume

    @JS fun maybe(player: Player?): Int = if (player == null) 0 else 1

    @JS @BufferMode(Buffer.YES) fun optedIn(player: Player): Int = 0
  }

  private fun describe(sharedClass: Class<out SharedObject>) =
    requireNotNull(SharedObjectRegistry.entryOrNull(sharedClass)) {
      "${sharedClass.name} did not register itself"
    }

  private val playerDefinition = describe(Player::class.java).definition
  private val moduleDefinition = ModuleBuilder().also { Players().`define$ExpoModulesV2`(it) }

  private fun args(jsName: String): List<IntArray> =
    moduleDefinition.functions.single { it.jsName == jsName }.argTypes.toList()

  private fun returns(jsName: String): IntArray =
    moduleDefinition.functions.single { it.jsName == jsName }.returnType

  private val playerClassId = SharedObjectRegistry.classIdFor(Player::class.java).value

  @Test
  fun `a shared object class fills the same builder a module does`() {
    assertEquals(
      listOf("play", "rename"),
      playerDefinition.functions.map { it.jsName },
    )
    assertEquals(listOf("volume"), playerDefinition.properties.map { it.jsName })
    assertEquals(listOf("stateChange"), playerDefinition.events.map { it.jsName })
    assertEquals("stateChange", Player().onStateChange.name)

    val volume = playerDefinition.properties.single()
    assertEquals("getVolume", volume.getterName)
    assertEquals("setVolume", volume.setterName)
  }

  @Test
  fun `define returns the JavaScript name`() {
    assertEquals("Player", describe(Player::class.java).jsName)
    assertEquals("Renamed", describe(Speaker::class.java).jsName)
  }

  @Test
  fun `a class that is not annotated declares nothing`() {
    // No annotation means nothing registered it, so a hand-described class is told to register
    // instead of silently exporting nothing.
    assertNull(SharedObjectRegistry.entryOrNull(object : SharedObject() {}.javaClass))
  }

  @Test
  fun `a shared object crosses as a reference carrying its class id`() {
    assertContentEquals(
      intArrayOf(CppType.SHARED_OBJECT.code, playerClassId),
      returns("create"),
    )
    assertContentEquals(
      listOf(intArrayOf(CppType.SHARED_OBJECT.code, playerClassId).toList()),
      args("volumeOf").map { it.toList() },
    )
  }

  @Test
  fun `a nullable shared object carries the nullable flag`() {
    assertContentEquals(
      listOf(intArrayOf(CppType.SHARED_OBJECT.code or CppType.NULLABLE, playerClassId).toList()),
      args("maybe").map { it.toList() },
    )
  }

  @Test
  fun `a shared object never rides the buffer, even when asked`() {
    // A reference identifies a native object; there is nothing to flatten, so Buffer.YES is a
    // no-op rather than an error.
    val codes = args("optedIn").single()
    assertEquals(0, codes[0] and CppType.USES_BUFFER)
    assertContentEquals(intArrayOf(CppType.SHARED_OBJECT.code, playerClassId), codes)
  }

  @Test
  fun `each class gets its own id`() {
    assertNotEquals(
      SharedObjectRegistry.classIdFor(Player::class.java),
      SharedObjectRegistry.classIdFor(Speaker::class.java),
    )
    // Stable across lookups: the id is what a declared type encodes, so it cannot drift.
    assertEquals(
      SharedObjectRegistry.classIdFor(Player::class.java),
      SharedObjectRegistry.classIdFor(Player::class.java),
    )
  }
}
