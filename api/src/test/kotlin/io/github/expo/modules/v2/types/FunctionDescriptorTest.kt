package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.converters.FunctionConverter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the code stream a lambda emits, which the C++ `ExpectedTypeDecoder` reads back. */
class FunctionDescriptorTest {
  private val int = CommonDescriptors.INT_BOXED
  private val nullableString = CommonDescriptors.STRING_NULL
  private val unit = TypeDescriptor.Simple(Unit::class.java, false)

  private fun function(
    params: Array<out TypeDescriptor.ObjectLike>,
    returns: TypeDescriptor.ObjectLike,
    isSuspend: Boolean = false,
    isNullable: Boolean = false,
  ) = TypeDescriptor.Function(params, returns, isSuspend, isNullable)

  private fun codesOf(descriptor: TypeDescriptor): List<Int> = AnyType(descriptor).codes.values.toList()

  @Test
  fun `a function is the head code, the suspend flag, the parameter count, then the types`() {
    assertEquals(
      listOf(CppType.FUNCTION.code, 0, 1, CppType.BOX_INT.code, CppType.UNIT.code),
      codesOf(function(arrayOf(int), unit)),
    )
  }

  @Test
  fun `parameters keep their order and nullability`() {
    assertEquals(
      listOf(
        CppType.FUNCTION.code, 0, 2,
        CppType.BOX_INT.code, CppType.STRING.nullable().code,
        CppType.STRING.nullable().code,
      ),
      codesOf(function(arrayOf(int, nullableString), nullableString)),
    )
  }

  @Test
  fun `a function without parameters`() {
    assertEquals(
      listOf(CppType.FUNCTION.code, 0, 0, CppType.BOX_INT.code),
      codesOf(function(emptyArray(), int)),
    )
  }

  @Test
  fun `suspend and nullable ride on the head and the flag`() {
    assertEquals(
      listOf(CppType.FUNCTION.nullable().code, 1, 1, CppType.BOX_INT.code, CppType.BOX_INT.code),
      codesOf(function(arrayOf(int), int, isSuspend = true, isNullable = true)),
    )
  }

  @Test
  fun `a parameter that is a container nests its own codes`() {
    val list = TypeDescriptor.Parametrized(List::class.java, false, arrayOf(int))
    assertEquals(
      listOf(CppType.FUNCTION.code, 0, 2, CppType.LIST.code, CppType.BOX_INT.code, CppType.BOX_INT.code, CppType.UNIT.code),
      codesOf(function(arrayOf(list, int), unit)),
    )
  }

  @Test
  fun `a function describes itself`() {
    assertEquals("(Integer, String?) -> Unit", function(arrayOf(int, nullableString), unit).toString())
    assertEquals("suspend () -> Integer", function(emptyArray(), int, isSuspend = true).toString())
    assertEquals("(Integer) -> Unit?", function(arrayOf(int), unit, isNullable = true).toString())
  }

  @Test
  fun `a function is nullable as declared`() {
    assertTrue(function(emptyArray(), unit, isNullable = true).isNullable)
    assertEquals(false, function(emptyArray(), unit).isNullable)
  }

  @Test
  fun `it resolves to the function converter and never buffers`() {
    val converter = function(arrayOf(int), unit).converter
    assertIs<FunctionConverter>(converter)
    assertEquals(false, converter.isPassthrough)
    assertEquals(false, AnyType(function(arrayOf(int), unit)).codes.usesBuffer)
  }

  @Test
  fun `a function only crosses inbound`() {
    val converter = function(arrayOf(int), unit).anyConverter
    assertFailsWith<IllegalStateException> { converter.toJni({ }) }
  }

  @Test
  fun `null only arrives in a nullable function slot`() {
    assertNull(function(arrayOf(int), unit, isNullable = true).anyConverter.fromJni(null))
    assertFailsWith<IllegalArgumentException> { function(arrayOf(int), unit).anyConverter.fromJni(null) }
  }
}
