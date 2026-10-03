package io.github.expo.modules.v2.sharedobjects

import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.modules.ModuleBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SharedObjectRegistryReserveTest {
  private class Reserved : SharedObject()

  private class NeverRegistered : SharedObject()

  @Test
  fun `a reserved class has its id before it is registered, and keeps it`() {
    val id = SharedObjectRegistry.reserve(Reserved::class.java)

    assertEquals(id, SharedObjectRegistry.reserve(Reserved::class.java))
    assertEquals(id, SharedObjectRegistry.classIdFor(Reserved::class.java).value)

    assertEquals(id, SharedObjectRegistry.register("Reserved", Reserved::class.java, ModuleBuilder()))
    assertEquals(id, SharedObjectRegistry.classIdFor(Reserved::class.java).value)
    assertEquals(id, SharedObjectRegistry.reserve(Reserved::class.java))
  }

  @Test
  fun `a class nobody reserved or registered has no id`() {
    val error = assertFailsWith<IllegalArgumentException> {
      SharedObjectRegistry.classIdFor(NeverRegistered::class.java)
    }
    assertTrue("not annotated" in error.message.orEmpty(), "unexpected message: ${error.message}")
  }
}
