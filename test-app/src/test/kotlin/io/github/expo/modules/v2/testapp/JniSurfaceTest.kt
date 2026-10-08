package io.github.expo.modules.v2.testapp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every Kotlin class, method and field the bridge's C++ names through JNI, read from the C++ sources
 * themselves: each kolibri `descriptor` and each `Method`, `StaticMethod`, `Field`, `StaticField`
 * and `Constructor` declared after it.
 *
 * This catches a Kotlin rename the C++ did not follow, including on paths no other test reaches:
 * C++ resolves most of these names only on first use.
 */
class JniSurfaceTest {
  private class Lookup(val jniName: String) {
    val methods = mutableSetOf<String>()
    val fields = mutableSetOf<String>()
    var constructed = false
  }

  private val lookups: Collection<Lookup> by lazy {
    val root = File(
      requireNotNull(System.getProperty(CPP_DIR_PROPERTY)) { "$CPP_DIR_PROPERTY is not set" }
    )
    val token = Regex(
      listOf(
        """descriptor\s*=\s*"([^"]+)"""",
        """\b(Method|StaticMethod|Field|StaticField)\s*<\s*"([^"]+)"""",
        """\bConstructor\s*<""",
      ).joinToString("|")
    )
    val byName = sortedMapOf<String, Lookup>()
    root.walkTopDown().filter { it.isFile && it.extension in setOf("h", "cpp") }.forEach { file ->
      // A member belongs to the descriptor declared before it in the same file.
      var current: Lookup? = null
      token.findAll(file.readText()).forEach { match ->
        val (descriptor, kind, member) = match.destructured
        when {
          descriptor.isNotEmpty() -> current = byName.getOrPut(descriptor) { Lookup(descriptor) }
          kind.endsWith("Field") -> current?.fields?.add(member)
          kind.isNotEmpty() -> current?.methods?.add(member)
          else -> current?.constructed = true
        }
      }
    }
    // The JDK's and the standard library's own classes are not this project's to keep.
    byName.values.filterNot { it.jniName.startsWith("java/") || it.jniName.startsWith("kotlin/") }
  }

  @Test
  fun `the C++ sources name the runtime's classes`() {
    assertTrue(lookups.size >= 10, "found only ${lookups.map { it.jniName }} - is the parser broken?")
  }

  @Test
  fun `every class, method and field the C++ names exists`() {
    val missing = lookups.flatMap { lookup ->
      val type = try {
        Class.forName(lookup.jniName.replace('/', '.'), false, javaClass.classLoader)
      } catch (_: ClassNotFoundException) {
        return@flatMap listOf("class ${lookup.jniName}")
      }
      val methods = type.declaredMethods.mapTo(mutableSetOf()) { it.name }
      val fields = type.declaredFields.mapTo(mutableSetOf()) { it.name }
      val constructorMissing = lookup.constructed && type.declaredConstructors.isEmpty()
      lookup.methods.filterNot { it in methods }.map { "method ${lookup.jniName}.$it" } +
        lookup.fields.filterNot { it in fields }.map { "field ${lookup.jniName}.$it" } +
        listOfNotNull("constructor of ${lookup.jniName}".takeIf { constructorMissing })
    }
    if (missing.isNotEmpty()) {
      fail("The C++ names what Kotlin does not have:\n" + missing.joinToString("\n"))
    }
  }

  private companion object {
    const val CPP_DIR_PROPERTY = "expo.modules.v2.cppDir"
  }
}
