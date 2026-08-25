package expo.modules.v2.types

import expo.modules.v2.converters.UrlConverter
import expo.modules.v2.records.RecordField
import expo.modules.v2.records.RecordSchema
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import expo.modules.v2.jsi.JavaScriptObject

/**
 * Pins the code stream a descriptor emits. Every expectation is the literal [TypeCodes] sequence
 * the resolved converter produces, so a change to the emitter that would desync the C++ `CppType`
 * reader fails mechanically.
 */
class TypeDescriptorTest {
  private val int = AnyType(TypeDescriptor.Int)
  private val nullableInt = AnyType(TypeDescriptor.Simple(Int::class.javaObjectType, true))
  private val any = AnyType(TypeDescriptor.Simple(Any::class.java, false))
  private val nullableAny = AnyType(TypeDescriptor.Simple(Any::class.java, true))

  @Test
  fun `descriptors describe themselves`() {
    listOf(
      TypeDescriptor.Int,
      TypeDescriptor.Bool,
      TypeDescriptor.Long,
      TypeDescriptor.Float,
      TypeDescriptor.Double,
    ).forEach { descriptor ->
      assertEquals(descriptor.cppType.toString(), descriptor.toString())
    }

    listOf(
      TypeDescriptor.BooleanArray(false),
      TypeDescriptor.IntArray(false),
      TypeDescriptor.LongArray(false),
      TypeDescriptor.FloatArray(false),
      TypeDescriptor.DoubleArray(false),
      TypeDescriptor.ByteArray(false),
    ).forEach { descriptor ->
      assertEquals(descriptor.cppType.toString(), descriptor.toString())
    }

    assertEquals("${CppType.INT_ARRAY}?", TypeDescriptor.IntArray(true).toString())
    assertEquals("String", TypeDescriptor.Simple(String::class.java, false).toString())
    assertEquals("String?", TypeDescriptor.Simple(String::class.java, true).toString())
    assertEquals(
      "List<Map<String, Integer?>?>?",
      TypeDescriptor.Parametrized(
        List::class.java,
        true,
        arrayOf(
          TypeDescriptor.Parametrized(
            Map::class.java,
            true,
            arrayOf(
              TypeDescriptor.Simple(String::class.java, false),
              TypeDescriptor.Simple(Int::class.javaObjectType, true),
            ),
          ),
        ),
      ).toString(),
    )
  }

  @Test
  fun `ANY carries nullability like every other reference type`() {
    // Dynamic is not optional: the flag is what distinguishes `Any` from `Any?`. It adds no buffer
    // byte (the tagged encoding represents null itself) but it is a declaration either way.
    assertContentEquals(intArrayOf(CppType.ANY.code or CppType.NULLABLE), nullableAny.codes.values)
    assertTrue(nullableAny.isNullable)
    assertFalse(any.isNullable)
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.ANY.code or CppType.NULLABLE),
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Any::class.java, true)),
        ),
      ).codes.values,
    )
  }

  @Test
  fun `JS_OBJECT can be marked nullable`() {
    AnyType(TypeDescriptor.Simple(JavaScriptObject::class.java, true))
  }

  @Test
  fun `nullable primitives use boxed leaf descriptors`() {
    assertContentEquals(intArrayOf(CppType.BOX_INT.code or CppType.NULLABLE), nullableInt.codes.values)
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.BOX_INT.code),
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
        ),
      ).codes.values,
    )
    assertContentEquals(
      intArrayOf(CppType.LIST.code, CppType.BOX_INT.code or CppType.NULLABLE),
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, true)),
        ),
      ).codes.values,
    )
    assertFalse(AnyType(TypeDescriptor.Simple(Int::class.javaObjectType, false)).isNullable)
  }

  @Test
  fun `a map declares its value type only`() {
    val intMap = AnyType(
      TypeDescriptor.Parametrized(
        Map::class.java,
        false,
        arrayOf(
          TypeDescriptor.Simple(String::class.java, false),
          TypeDescriptor.Simple(Int::class.javaObjectType, false),
        ),
      ),
    )
    assertContentEquals(intArrayOf(CppType.MAP.code, CppType.BOX_INT.code), intMap.codes.values)
    // The key is part of the Kotlin type but never of the code stream.
    assertEquals(2, (intMap.descriptor as TypeDescriptor.Parametrized).params.size)
  }

  @Test
  fun `buffered marks the head code only`() {
    assertContentEquals(
      intArrayOf(CppType.LIST.code or CppType.USES_BUFFER, CppType.BOX_DOUBLE.code),
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
        ),
      ).buffered().codes.values,
    )
  }

  @Test
  fun `buffered leaves mark their single code`() {
    assertContentEquals(
      intArrayOf(CppType.STRING.code or CppType.USES_BUFFER),
      AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered().codes.values,
    )
    assertContentEquals(
      intArrayOf(CppType.BOX_INT.code or CppType.NULLABLE or CppType.USES_BUFFER),
      nullableInt.buffered().codes.values,
    )
    assertContentEquals(
      intArrayOf(CppType.DOUBLE_ARRAY.code or CppType.USES_BUFFER),
      AnyType(TypeDescriptor.DoubleArray(false)).buffered().codes.values,
    )
    assertContentEquals(
      intArrayOf(CppType.BYTE_ARRAY.code or CppType.USES_BUFFER),
      AnyType(TypeDescriptor.ByteArray(false)).buffered().codes.values,
    )
  }

  @Test
  fun `buffered composes with nullable`() {
    val nullableInts = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        true,
        arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
      ),
    )
    assertContentEquals(
      intArrayOf(
        CppType.LIST.code or CppType.NULLABLE or CppType.USES_BUFFER,
        CppType.BOX_INT.code,
      ),
      nullableInts.buffered().codes.values,
    )
  }

  @Test
  fun `a converted type emits its bridge codes`() {
    // A converter contributes no codes of its own; the use site's nullability and transport land on
    // the bridge head, exactly as they would for a plain slot of that bridge type.
    val url = AnyType(TypeDescriptor.Simple(URL::class.java, false))
    val nullableUrl = AnyType(TypeDescriptor.Simple(URL::class.java, true))
    assertContentEquals(intArrayOf(CppType.STRING.code), url.codes.values)
    assertContentEquals(intArrayOf(CppType.STRING.code or CppType.NULLABLE), nullableUrl.codes.values)
    assertContentEquals(
      intArrayOf(CppType.STRING.code or CppType.USES_BUFFER),
      url.buffered().codes.values,
    )
    assertTrue(url.converter is UrlConverter)
    // The nullable declaration resolves its own converter, which states the same nullability.
    assertEquals(true, nullableUrl.converter.isNullable)
  }

  @Test
  fun `the variants match the shape of the Kotlin type`() {
    assertTrue(int.descriptor is TypeDescriptor.Primitive)
    val ints = TypeDescriptor.Parametrized(
      List::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
    )
    assertEquals(1, ints.params.size)
  }

  @Test
  fun `equal types resolve one structure`() {
    // Scalars share their singleton Primitive descriptor; composite declarations are distinct
    // instances that emit identical code streams.
    assertSame(int.descriptor, TypeDescriptor.Int)
    assertContentEquals(
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
        ),
      ).codes.values,
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
        ),
      ).codes.values,
    )
  }
}
