package io.github.expo.modules.v2.modules

import io.github.expo.modules.v2.annotations.JS
import io.github.expo.modules.v2.annotations.Record
import io.github.expo.modules.v2.binary.ModuleDescriptorEncoder
import io.github.expo.modules.v2.sharedobjects.SharedObjectRegistry
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.buffered
import io.github.expo.kolibri.binary.BinaryBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.github.expo.modules.v2.types.TypeDescriptor

private const val TRAMPOLINE = "__construct\$ExpoModulesV2"

@Record
private data class EncoderTestRecord(val x: Int) : io.github.expo.modules.v2.records.Record

@JS
private class EncoderTestSharedObject(
  @Suppress("unused") val greeting: String,
) : io.github.expo.modules.v2.sharedobjects.SharedObject()

/**
 * Exercises the module-descriptor layout on a privately allocated buffer — no natives involved.
 * The C++ counterpart (`ModulesHostObject::get` in JavaScriptRuntime.cpp) reads exactly this layout.
 */
class ModuleDescriptorEncoderTest {
  private fun definitions(build: ModuleBuilder.() -> Unit) = ModuleBuilder().apply(build).functions

  private fun BinaryBuffer.readString(): String = getString()

  private fun BinaryBuffer.readIntArray(): List<Int> = List(getInt()) { getInt() }

  @Test
  fun `encodes a module descriptor in the documented layout`() {
    val functions = definitions {
      function("add", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(TypeDescriptor.Int))
      function(
        "join",
        AnyType(
          TypeDescriptor.Parametrized(
            List::class.java,
            false,
            arrayOf(TypeDescriptor.Simple(String::class.java, false)),
          ),
        ).buffered(),
        returns = AnyType(TypeDescriptor.Simple(String::class.java, false)),
        methodName = "join__trampoline",
      )
    }
    val buf = BinaryBuffer.allocate(1024)

    val size = ModuleDescriptorEncoder.encode(functions, emptyList(), buf)

    val reader = buf.duplicateView()
    reader.limit = size // reading past the payload must fail, not return zeros
    assertEquals(size, reader.getInt(), "the leading i32 carries the payload size")
    assertEquals(2, reader.getInt())

    assertEquals("add", reader.readString())
    assertEquals("add", reader.readString())
    assertEquals(0, reader.getInt(), "no flags: a synchronous export")
    assertEquals(2, reader.getInt())
    repeat(2) {
      assertEquals(listOf(CppType.INT.code), reader.readIntArray())
    }
    assertEquals(listOf(CppType.INT.code), reader.readIntArray())

    assertEquals("join", reader.readString())
    assertEquals("join__trampoline", reader.readString())
    assertEquals(0, reader.getInt(), "no flags: a synchronous export")
    assertEquals(1, reader.getInt())
    assertEquals(
      listOf(CppType.LIST.code or CppType.USES_BUFFER, CppType.STRING.code),
      reader.readIntArray(),
      "the buffered() flag rides the head code in the descriptor",
    )
    assertEquals(listOf(CppType.STRING.code), reader.readIntArray())

    assertEquals(0, reader.getInt(), "no properties declared")
    assertEquals(0, reader.getInt(), "no constructable shared classes declared")

    assertEquals(size, reader.position, "trailing bytes in the payload")
  }

  @Test
  fun `encodes selected JVM property accessors`() {
    val definition = ModuleBuilder().apply {
      property("version", AnyType(TypeDescriptor.Simple(String::class.java, false)), propertyName = "nativeVersion")
      property(
        "homepage",
        AnyType(TypeDescriptor.Simple(java.net.URL::class.java, false)),
        mutable = true,
        propertyName = "homepage__trampoline",
        setterType = AnyType(TypeDescriptor.Simple(java.net.URL::class.java, false)).buffered(),
      )
    }
    val buf = BinaryBuffer.allocate(1024)

    val size = ModuleDescriptorEncoder.encode(
      definition.functions,
      definition.properties,
      buf,
    )

    val reader = buf.duplicateView()
    reader.limit = size
    assertEquals(size, reader.getInt())
    assertEquals(0, reader.getInt(), "no functions declared")
    assertEquals(2, reader.getInt())

    assertEquals("version", reader.readString())
    assertEquals("getNativeVersion", reader.readString())
    assertEquals(listOf(CppType.STRING.code), reader.readIntArray())
    assertEquals(false, reader.getBoolean(), "read-only property")

    // The selected property name resolves both JVM accessors before encoding, and each accessor
    // carries the transport of its own direction.
    assertEquals("homepage", reader.readString())
    assertEquals("getHomepage__trampoline", reader.readString())
    assertEquals(listOf(CppType.STRING.code), reader.readIntArray())
    assertEquals(true, reader.getBoolean(), "mutable property")
    assertEquals("setHomepage__trampoline", reader.readString())
    assertEquals(
      listOf(CppType.STRING.code or CppType.USES_BUFFER),
      reader.readIntArray(),
      "the setter was declared buffered",
    )
    assertEquals(0, reader.getInt(), "no constructable shared classes declared")
    assertEquals(size, reader.position)
  }

  @Test
  fun `encodes the selected JVM method name`() {
    val functions = definitions {
      function(
        "echo",
        AnyType(TypeDescriptor.Simple(EncoderTestRecord::class.java, false)).buffered(),
        returns = AnyType(
          TypeDescriptor.Simple(EncoderTestRecord::class.java, false),
        ).buffered(),
        methodName = "echo__trampoline",
      )
    }
    val buf = BinaryBuffer.allocate(1024)

    val size = ModuleDescriptorEncoder.encode(functions, emptyList(), buf)

    val reader = buf.duplicateView()
    reader.limit = size
    reader.getInt() // payloadEnd
    assertEquals(1, reader.getInt())
    assertEquals("echo", reader.readString())
    assertEquals("echo__trampoline", reader.readString())
    assertEquals(0, reader.getInt()) // flags
    assertEquals(1, reader.getInt()) // argCount follows the method metadata
  }

  @Test
  fun `an async export sets the async flag`() {
    val functions = definitions {
      function(
        "load",
        returns = AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
        methodName = "load__trampoline",
        isAsync = true,
      )
    }
    val buf = BinaryBuffer.allocate(1024)

    val size = ModuleDescriptorEncoder.encode(functions, emptyList(), buf)

    val reader = buf.duplicateView()
    reader.limit = size
    reader.getInt() // payloadEnd
    assertEquals(1, reader.getInt())
    assertEquals("load", reader.readString())
    assertEquals("load__trampoline", reader.readString())
    assertEquals(
      ModuleFunctionDefinition.FLAG_ASYNC,
      reader.getInt(),
      "bit 0 tells native to hand the trampoline a Promise",
    )
    assertEquals(0, reader.getInt(), "no arguments")
  }

  @Test
  fun `a zero-function module encodes a valid header`() {
    val buf = BinaryBuffer.allocate(64)

    val size = ModuleDescriptorEncoder.encode(emptyList(), emptyList(), buf)

    val reader = buf.duplicateView()
    reader.limit = size
    assertEquals(size, reader.getInt(), "the leading i32 carries the payload size")
    assertEquals(0, reader.getInt(), "no functions")
    assertEquals(0, reader.getInt(), "no properties")
    assertEquals(0, reader.getInt(), "no constructable shared classes")
    assertEquals(size, reader.position, "trailing bytes in the payload")
  }

  @Test
  fun `encodes a constructable shared class`() {
    val definition = ModuleBuilder().apply {
      sharedClass(
        "Greeter",
        EncoderTestSharedObject::class.java,
        AnyType(TypeDescriptor.Simple(String::class.java, false)),
        trampolineName = TRAMPOLINE,
      )
    }
    val buf = BinaryBuffer.allocate(1024)

    val size = ModuleDescriptorEncoder.encode(
      definition.functions,
      definition.properties,
      buf,
      definition.sharedClasses,
    )

    val reader = buf.duplicateView()
    reader.limit = size
    assertEquals(size, reader.getInt())
    assertEquals(0, reader.getInt(), "no functions")
    assertEquals(0, reader.getInt(), "no properties")
    assertEquals(1, reader.getInt(), "one constructable class")

    assertEquals("Greeter", reader.readString())
    // The registry's id for the class, which the façade path keys on too.
    assertEquals(
      SharedObjectRegistry.classIdFor(EncoderTestSharedObject::class.java).value,
      reader.getInt(),
    )
    assertEquals(TRAMPOLINE, reader.readString(), "the constructor trampoline")
    assertEquals(1, reader.getInt(), "one constructor argument")
    assertEquals(listOf(CppType.STRING.code), reader.readIntArray())
    assertEquals(size, reader.position, "trailing bytes in the payload")
  }

  @Test
  fun `encodes the trampoline of a shared class with a buffered constructor argument`() {
    val definition = ModuleBuilder().apply {
      sharedClass(
        "Greeter",
        EncoderTestSharedObject::class.java,
        AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
        trampolineName = TRAMPOLINE,
      )
    }
    val buf = BinaryBuffer.allocate(1024)

    val size = ModuleDescriptorEncoder.encode(
      definition.functions,
      definition.properties,
      buf,
      definition.sharedClasses,
    )

    val reader = buf.duplicateView()
    reader.limit = size
    assertEquals(size, reader.getInt())
    assertEquals(0, reader.getInt(), "no functions")
    assertEquals(0, reader.getInt(), "no properties")
    assertEquals(1, reader.getInt(), "one constructable class")

    assertEquals("Greeter", reader.readString())
    assertEquals(
      SharedObjectRegistry.classIdFor(EncoderTestSharedObject::class.java).value,
      reader.getInt(),
    )
    assertEquals(TRAMPOLINE, reader.readString(), "the constructor trampoline")
    assertEquals(1, reader.getInt(), "one constructor argument")
    assertEquals(
      listOf(CppType.STRING.code or CppType.USES_BUFFER),
      reader.readIntArray(),
    )
    assertEquals(size, reader.position, "trailing bytes in the payload")
  }

  @Test
  fun `a descriptor that does not fit the buffer throws`() {
    val functions = definitions {
      function("veryLongFunctionName", returns = AnyType(TypeDescriptor.Int))
    }
    val buf = BinaryBuffer.allocate(16)

    // The raw overflow escapes here; ModuleRegistry.encodeModule wraps it, naming the module.
    assertFailsWith<java.nio.BufferOverflowException> {
      ModuleDescriptorEncoder.encode(functions, emptyList(), buf)
    }
  }
}
