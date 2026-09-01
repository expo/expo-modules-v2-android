package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.records.SchemaId

@JvmInline
value class CppType(val code: Int) {
  fun nullable(isNullable: Boolean = true): CppType =
    if (isNullable) {
      CppType(code or NULLABLE)
    } else {
      this
    }

  operator fun plus(tail: TypeCodes): TypeCodes {
    val out = IntArray(1 + tail.size)
    out[0] = code
    tail.values.copyInto(out, 1)
    return TypeCodes(out)
  }

  internal operator fun plus(schemaId: SchemaId): TypeCodes {
    return TypeCodes.of(code, schemaId.value)
  }

  fun toTypeCodes(): TypeCodes =
    TypeCodes.of(this.code)

  val codeWithoutHeader get() = code and HEAD_FLAGS.inv()
  val isNullable: Boolean get() = code and NULLABLE != 0

  val usesBuffer: Boolean get() = code and USES_BUFFER != 0

  override fun toString(): String {
    val name = when (CppType(codeWithoutHeader)) {
      BOOLEAN -> "boolean"
      STRING -> "string"
      INT -> "int"
      LONG -> "long"
      FLOAT -> "float"
      DOUBLE -> "double"
      LIST -> "list"
      MAP -> "map"
      ANY -> "any"
      DOUBLE_ARRAY -> "double_array"
      INT_ARRAY -> "int_array"
      LONG_ARRAY -> "long_array"
      FLOAT_ARRAY -> "float_array"
      BOOLEAN_ARRAY -> "boolean_array"
      BYTE_ARRAY -> "byte_array"
      RECORD -> "record"
      JS_VALUE -> "js_value"
      JS_OBJECT -> "js_object"
      UNIT -> "unit"
      BOX_INT -> "box_int"
      BOX_BOOLEAN -> "box_boolean"
      BOX_LONG -> "box_long"
      BOX_FLOAT -> "box_float"
      BOX_DOUBLE -> "box_double"
      else -> "unknown(code=$code)"
    }

    return name +
      (if (isNullable) "?" else "") +
      (if (usesBuffer) " (buffered)" else "")
  }

  companion object {
    //@formatter:off
    val BOOLEAN = CppType(0)
    val STRING = CppType(1)
    val INT = CppType(2)
    val LONG = CppType(3)
    val FLOAT = CppType(4)
    val DOUBLE = CppType(5)

    val LIST = CppType(6)
    val MAP = CppType(7)

    val ANY = CppType(8)

    val DOUBLE_ARRAY = CppType(9)
    val INT_ARRAY = CppType(10)
    val LONG_ARRAY = CppType(11)
    val FLOAT_ARRAY = CppType(12)
    val BOOLEAN_ARRAY = CppType(13)
    val BYTE_ARRAY = CppType(14)

    val RECORD = CppType(15)

    val JS_VALUE = CppType(16)
    val JS_OBJECT = CppType(17)

    val UNIT = CppType(18)

    val BOX_INT = CppType(19)
    val BOX_BOOLEAN = CppType(20)
    val BOX_LONG = CppType(21)
    val BOX_FLOAT = CppType(22)
    val BOX_DOUBLE = CppType(23)
    //@formatter:on

    const val NULLABLE = 0x100
    const val USES_BUFFER = 0x200
    internal const val HEAD_FLAGS = NULLABLE or USES_BUFFER
  }
}
