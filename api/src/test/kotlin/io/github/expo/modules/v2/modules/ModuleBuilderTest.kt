package io.github.expo.modules.v2.modules

import io.github.expo.modules.v2.annotations.Record
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.records.codecFor
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.buffered
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.expo.modules.v2.jsi.JavaScriptObject

@Record
private data class PlainRec(val n: Int) : io.github.expo.modules.v2.records.Record

@Record(name = "FlaggedRecB", bufferSafe = false)
private class FlaggedRec(val h: JavaScriptObject) : io.github.expo.modules.v2.records.Record

/**
 * Pins what a declaration records: the code stream each argument and return type emits, the
 * transport flag [buffered] marks on it, and the JVM method/accessor names the bridge resolves.
 * A trampoline is a declaration's own statement of which method to call — the builder records it
 * and does not second-guess which types need one.
 */
class ModuleBuilderTest {
  private val int = AnyType(TypeDescriptor.Int)
  private val string = AnyType(TypeDescriptor.Simple(String::class.java, false))
  private val any = AnyType(TypeDescriptor.Simple(Any::class.java, false))
  private val url = AnyType(TypeDescriptor.Simple(URL::class.java, false))

  private fun builder() = ModuleBuilder()

  @Test
  fun `ANY-only signatures are invoked directly`() {
    val direct = builder()
    direct.function("f", any, returns = any)
    assertEquals(CppType.ANY.code, direct.functions[0].argTypes.single().single())
    assertEquals(CppType.ANY.code, direct.functions[0].returnType.single())
    assertEquals("f", direct.functions[0].methodName)

    builder().function(
      "g",
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Any::class.java, false)),
        ),
      ),
      returns = AnyType(
        TypeDescriptor.Parametrized(
          Map::class.java,
          false,
          arrayOf(
            TypeDescriptor.Simple(String::class.java, false),
            TypeDescriptor.Simple(Any::class.java, false),
          ),
        ),
      ),
    )
  }

  @Test
  fun `Unit arguments and returns are direct JNI slots`() {
    val builder = builder()
    builder.function("accept", AnyType(TypeDescriptor.Simple(Unit::class.java, false)), returns = string)
    builder.function("clear", returns = AnyType(TypeDescriptor.Simple(Unit::class.java, false)))

    assertEquals(CppType.UNIT.code, builder.functions[0].argTypes.single().single())
    assertEquals(CppType.UNIT.code, builder.functions[1].returnType.single())
  }

  @Test
  fun `JavaScript export names are unique across functions and properties`() {
    val builder = builder()
    builder.function("value", returns = int)

    val collision = assertFailsWith<IllegalArgumentException> {
      builder.property("value", int)
    }
    assertTrue("already declared" in collision.message!!, collision.message)

    val duplicate = assertFailsWith<IllegalArgumentException> {
      builder.function("value", returns = string)
    }
    assertTrue("already declared" in duplicate.message!!, duplicate.message)
  }

  @Test
  fun `buffered values mark the transport flag on their head code`() {
    val cases = listOf(
      AnyType(TypeDescriptor.Simple(PlainRec::class.java, false)),
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
        ),
      ),
      AnyType(
        TypeDescriptor.Parametrized(
          Map::class.java,
          false,
          arrayOf(
            TypeDescriptor.Simple(String::class.java, false),
            TypeDescriptor.Simple(Int::class.javaObjectType, false),
          ),
        ),
      ),
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(PlainRec::class.java, false)),
        ),
      ),
    )
    for (type in cases) {
      val builder = builder()
      builder.function("f", type.buffered(), returns = int, methodName = "f__trampoline")
      val declared = builder.functions.single()
      assertEquals(
        CppType.USES_BUFFER,
        declared.argTypes.single()[0] and CppType.USES_BUFFER,
        "$type should carry the transport flag on its head code",
      )
      // Only the head code is marked; the element codes stay untouched.
      assertTrue(declared.argTypes.single().drop(1).none { it and CppType.USES_BUFFER != 0 })
      // The trampoline is the method the bridge resolves; the direct name is never recorded.
      assertEquals("f__trampoline", declared.methodName)
    }
  }

  @Test
  fun `unbuffered record-free containers are direct JNI object slots`() {
    // Transport is explicit: without buffered() a typed container crosses as a JList/JMap slot
    // and the user method is invoked directly.
    val direct = builder()
    direct.function(
      "f",
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
        ),
      ),
      returns = int,
    )
    builder().function(
      "g",
      AnyType(
        TypeDescriptor.Parametrized(
          Map::class.java,
          false,
          arrayOf(
            TypeDescriptor.Simple(String::class.java, false),
            TypeDescriptor.Simple(Int::class.javaObjectType, false),
          ),
        ),
      ),
      returns = int,
    )
    // The head code carries no transport flag, so native reads a plain object slot.
    assertEquals(CppType.LIST.code, direct.functions[0].argTypes.single()[0])

    // An unbuffered record still Map-rides, so the declaration names the trampoline that does the
    // fromMap/toMap; the builder records that name in place of the direct method.
    builder().function(
      "h",
      AnyType(TypeDescriptor.Simple(PlainRec::class.java, false)),
      returns = int,
      methodName = "h__trampoline",
    )
    builder().function(
      "i",
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(PlainRec::class.java, false)),
        ),
      ),
      returns = int,
      methodName = "i__trampoline",
    )
  }

  @Test
  fun `a record declares its schema id after the RECORD code`() {
    val record = AnyType(TypeDescriptor.Simple(FlaggedRec::class.java, false))
    val schemaId = RecordRegistry.typeFor(codecFor<FlaggedRec>()).schemaId.value

    val builder = builder()
    builder.function("f", record, returns = int, methodName = "f__trampoline")
    assertContentEquals(intArrayOf(CppType.RECORD.code, schemaId), builder.functions[0].argTypes.single())

    // Inside a container the record's pair follows the container head, in the same pre-order.
    builder.function(
      "g",
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(FlaggedRec::class.java, false)),
        ),
      ),
      returns = int,
      methodName = "g__trampoline",
    )
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.RECORD.code, schemaId),
      builder.functions[1].argTypes.single(),
    )

    // The return position emits the same stream.
    builder.function("h", returns = record, methodName = "h__trampoline")
    assertContentEquals(intArrayOf(CppType.RECORD.code, schemaId), builder.functions[2].returnType)
  }

  @Test
  fun `functions support at most eight arguments`() {
    builder().function(
      "eight",
      int,
      int,
      int,
      int,
      int,
      int,
      int,
      int,
      returns = int,
    )
    val error = assertFailsWith<IllegalArgumentException> {
      builder().function(
        "nine",
        int,
        int,
        int,
        int,
        int,
        int,
        int,
        int,
        int,
        returns = int,
      )
    }
    assertTrue("at most 8" in error.message!!, error.message)
  }

  @Test
  fun `a built-in converted type declares its bridge codes`() {
    // A converter contributes no codes of its own, so native sees the bridge and the trampoline
    // does the conversion on the Kotlin side.
    val builder = builder()
    builder.function("f", url, returns = string, methodName = "f__trampoline")
    assertContentEquals(intArrayOf(CppType.STRING.code), builder.functions[0].argTypes.single())
  }

  @Test
  fun `properties resolve Kotlin val and var accessor names`() {
    val builder = builder()
    builder.property("version", string)
    builder.property("count", int, mutable = true, propertyName = "nativeCount")
    builder.property("ready", AnyType(TypeDescriptor.Bool), mutable = true, propertyName = "isReady")

    val version = builder.properties[0]
    assertEquals("getVersion", version.getterName)
    assertEquals(null, version.setterName)

    val count = builder.properties[1]
    assertEquals("getNativeCount", count.getterName)
    assertEquals("setNativeCount", count.setterName)

    val ready = builder.properties[2]
    assertEquals("isReady", ready.getterName)
    assertEquals("setReady", ready.setterName)
  }

  @Test
  fun `propertyName selects the accessor pair the bridge resolves`() {
    // A plain typed container is a direct JList-slot property, so its accessors are the Kotlin
    // property's own.
    val plain = builder()
    plain.property("items", AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
      ),
    ))
    assertEquals("getItems", plain.properties.single().getterName)

    val builder = builder()
    builder.property(
      "homepage",
      url,
      mutable = true,
      propertyName = "homepage__trampoline",
    )
    val property = builder.properties.single()
    // The selected property name resolves both JVM accessors; native only sees these.
    assertEquals("getHomepage__trampoline", property.getterName)
    assertEquals("setHomepage__trampoline", property.setterName)
    // The declared type is the converter's bridge, exactly as in a function signature.
    assertContentEquals(intArrayOf(CppType.STRING.code), property.getterType)
    assertContentEquals(intArrayOf(CppType.STRING.code), property.setterType)
  }

  @Test
  fun `a setter can cross on a different transport than its getter`() {
    val builder = builder()
    builder.property("name", string, mutable = true, setterType = string.buffered())
    builder.property("readOnly", string, setterType = string.buffered())

    val name = builder.properties[0]
    assertContentEquals(intArrayOf(CppType.STRING.code), name.getterType)
    assertContentEquals(intArrayOf(CppType.STRING.code or CppType.USES_BUFFER), name.setterType)

    // Nothing writes a `val`, so the setter type is dropped with the setter name.
    assertNull(builder.properties[1].setterType)
  }
}
