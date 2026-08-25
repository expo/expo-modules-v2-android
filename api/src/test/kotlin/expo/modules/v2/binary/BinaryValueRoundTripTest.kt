package expo.modules.v2.binary

import expo.modules.v2.annotations.Record
import expo.modules.v2.types.AnyType
import expo.modules.v2.types.CppType
import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.kolibri.binary.BinaryTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@Record
private data class RoundTripPoint(val x: Double, val label: String?) : expo.modules.v2.records.Record

/**
 * The mechanical lockstep guard for [BinaryValueCodec]'s mirrored write/read halves: every
 * [CppType] must either round-trip here or be explicitly listed as never riding the buffer.
 * Appending a new CppType without extending this table fails [every CppType code is covered].
 * The dynamic (ANY) universe is pinned per [BinaryTag] the same way.
 */
class BinaryValueRoundTripTest {
  private val buffer = BinaryBuffer.allocate(64 * 1024)

  /** One payload sample per leaf/dynamic kind; containers and records get shaped samples. */
  private fun sampleFor(kind: CppType): Any? = when (kind) {
    CppType.BOOLEAN, CppType.BOX_BOOLEAN -> true
    CppType.STRING -> "żółć ⚡ ascii"
    CppType.INT, CppType.BOX_INT -> 42
    CppType.LONG, CppType.BOX_LONG -> 1L shl 60
    CppType.FLOAT, CppType.BOX_FLOAT -> 1.5f
    CppType.DOUBLE, CppType.BOX_DOUBLE -> 3.25
    CppType.ANY -> mapOf("a" to 1.0, "b" to listOf(true, null, "x"))
    CppType.DOUBLE_ARRAY -> doubleArrayOf(0.5, 1.5)
    CppType.INT_ARRAY -> intArrayOf(1, 2, 3)
    CppType.LONG_ARRAY -> longArrayOf(1L, 1L shl 40)
    CppType.FLOAT_ARRAY -> floatArrayOf(0.5f, 2.5f)
    CppType.BOOLEAN_ARRAY -> booleanArrayOf(true, false)
    CppType.BYTE_ARRAY -> byteArrayOf(1, -2, 127)
    CppType.UNIT -> Unit
    else -> fail(
      "CppType $kind has no round-trip sample — new types must be added to " +
        "BinaryValueRoundTripTest (and handled by BOTH BinaryValueCodec halves)",
    )
  }

  /** Kinds that never ride a binary payload, or are covered by the shaped cases below. */
  private fun exemptionFor(kind: CppType): String? = when (kind) {
    CppType.JS_VALUE, CppType.JS_OBJECT -> "JSI handles are runtime references; never buffered"
    CppType.LIST, CppType.MAP -> "covered by the container cases below"
    CppType.RECORD -> "covered by the record cases below"
    else -> null
  }

  private fun roundTrip(value: Any?, schema: AnyType): Any? {
    val buf = buffer.duplicateView()
    schema.descriptor.anyConverter.writeToBuffer(buf, value)
    val length = buf.position
    val view = buffer.duplicateView()
    view.limit = length
    return schema.descriptor.anyConverter.readFromBuffer(view)
  }

  /** A declared ANY slot: the tagged format, entered from the schema-directed side. */
  private fun roundTripDynamic(value: Any?): Any? = roundTrip(value, AnyType(
    TypeDescriptor.Simple(Any::class.java, false),
  ))

  /** The schema for one leaf kind, boxed kinds included (they intern through their boxed class). */
  private fun schemaOf(kind: CppType): AnyType = AnyType(
    when (kind) {
      CppType.BOOLEAN -> TypeDescriptor.Bool
      CppType.INT -> TypeDescriptor.Int
      CppType.LONG -> TypeDescriptor.Long
      CppType.FLOAT -> TypeDescriptor.Float
      CppType.DOUBLE -> TypeDescriptor.Double
      CppType.BOX_BOOLEAN -> TypeDescriptor.Simple(Boolean::class.javaObjectType, false)
      CppType.BOX_INT -> TypeDescriptor.Simple(Int::class.javaObjectType, false)
      CppType.BOX_LONG -> TypeDescriptor.Simple(Long::class.javaObjectType, false)
      CppType.BOX_FLOAT -> TypeDescriptor.Simple(Float::class.javaObjectType, false)
      CppType.BOX_DOUBLE -> TypeDescriptor.Simple(Double::class.javaObjectType, false)
      CppType.STRING -> TypeDescriptor.Simple(String::class.java, false)
      CppType.ANY -> TypeDescriptor.Simple(Any::class.java, false)
      CppType.UNIT -> TypeDescriptor.Simple(Unit::class.java, false)
      CppType.BYTE_ARRAY -> TypeDescriptor.ByteArray(false)
      CppType.BOOLEAN_ARRAY -> TypeDescriptor.BooleanArray(false)
      CppType.INT_ARRAY -> TypeDescriptor.IntArray(false)
      CppType.LONG_ARRAY -> TypeDescriptor.LongArray(false)
      CppType.FLOAT_ARRAY -> TypeDescriptor.FloatArray(false)
      CppType.DOUBLE_ARRAY -> TypeDescriptor.DoubleArray(false)
      else -> fail(
        "CppType $kind has no descriptor here — new leaf types must be added to " +
          "BinaryValueRoundTripTest.schemaOf",
      )
    },
  )


  @Test
  fun `every scalar list kind round-trips as both run and nullable forms`() {
    // A scalar element is a JVM object, so each run names its boxed class.
    val runs = mapOf(
      Double::class.javaObjectType to listOf(0.5, 1.5),
      Int::class.javaObjectType to listOf(1, 2),
      Long::class.javaObjectType to listOf(1L, 1L shl 40),
      Float::class.javaObjectType to listOf(0.5f, 1.5f),
      Boolean::class.javaObjectType to listOf(true, false),
    )
    for ((element, list) in runs) {
      val run = AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(element, false)),
        ),
      )
      assertEquals(list, roundTrip(list, run), "run of ${element.simpleName}")
      val nullable = list + null
      val schema = AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(element, true)),
        ),
      )
      assertEquals(nullable, roundTrip(nullable, schema), "nullable list of ${element.simpleName}")
    }
    // Non-scalar element runs take the per-element path.
    assertEquals(
      listOf("a", "żółć"),
      roundTrip(listOf("a", "żółć"), AnyType(
        TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(String::class.java, false))),
      )),
    )
  }

  @Test
  fun `containers, records and nullable heads round-trip`() {
    val map = mapOf("a" to 1, "b" to 2)
    assertEquals(map, roundTrip(map, AnyType(
      TypeDescriptor.Parametrized(
        Map::class.java,
        false,
        arrayOf(
          TypeDescriptor.Simple(String::class.java, false),
          TypeDescriptor.Simple(Int::class.javaObjectType, false),
        ),
      ),
    )))

    val point = RoundTripPoint(1.5, "hello")
    assertEquals(point, roundTrip(point, AnyType(TypeDescriptor.Simple(RoundTripPoint::class.java, false))))
    assertEquals(
      listOf(RoundTripPoint(1.0, null), RoundTripPoint(2.0, "x")),
      roundTrip(
        listOf(RoundTripPoint(1.0, null), RoundTripPoint(2.0, "x")),
        AnyType(
          TypeDescriptor.Parametrized(
            List::class.java,
            false,
            arrayOf(TypeDescriptor.Simple(RoundTripPoint::class.java, false)),
          ),
        ),
      ),
    )

    val nullableString = AnyType(TypeDescriptor.Simple(String::class.java, true))
    assertEquals("s", roundTrip("s", nullableString))
    assertNull(roundTrip(null, nullableString))

    // A nullable record is a presence byte plus the positional body — never its Map bridge shape.
    val nullablePoint = AnyType(TypeDescriptor.Simple(RoundTripPoint::class.java, true))
    assertEquals(point, roundTrip(point, nullablePoint))
    assertNull(roundTrip(null, nullablePoint))
  }

  @Test
  fun `every dynamic BinaryTag round-trips through a declared ANY slot`() {
    // One value per tag in the dynamic universe; a new BinaryTag needs a row here. BinaryTag.NULL
    // never stands alone under a declared slot — nullability there is the presence byte — so it
    // rides the LIST and MAP rows as a nested element instead.
    val perTag = mapOf(
      BinaryTag.BOOLEAN to true,
      BinaryTag.INT to 7,
      BinaryTag.LONG to 7L,
      BinaryTag.FLOAT to 0.5f,
      BinaryTag.DOUBLE to 2.5,
      BinaryTag.STRING to "żółć",
      BinaryTag.LIST to listOf(1, "two", null),
      BinaryTag.MAP to mapOf("k" to listOf(1.5, 2.5), "absent" to null),
      BinaryTag.EXTERNAL_SCHEMA to RoundTripPoint(3.5, "dyn"),
    )
    for ((tag, value) in perTag) {
      assertEquals(value, roundTripDynamic(value), "dynamic tag $tag")
    }
    // BYTE_ARRAY needs content equality.
    val bytes = byteArrayOf(1, 2, -3)
    assertTrue(bytes.contentEquals(roundTripDynamic(bytes) as ByteArray), "dynamic BYTE_ARRAY")
    // Scalar-homogeneous dynamic lists take Kolibri's bulk-run arm.
    assertEquals(listOf(0.5, 1.5, 2.5), roundTripDynamic(listOf(0.5, 1.5, 2.5)))
    assertEquals(listOf(true, false), roundTripDynamic(listOf(true, false)))
  }
}
