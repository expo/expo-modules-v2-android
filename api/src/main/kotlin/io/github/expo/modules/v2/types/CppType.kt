package io.github.expo.modules.v2.types

/**
 * The kind of a value on the bridge: the first code of its type codes, without flags. [code] MUST
 * match `CppType` in `expo-modules-v2/descriptor/CppType.h`.
 *
 * A scalar kind is a register-width JNI slot when it stands alone and cannot be null; when it is
 * nullable, or inside a container, it crosses boxed (`java.lang.Integer` and so on).
 */
enum class CppType(val code: Int) {
  BOOLEAN(0),
  STRING(1),
  INT(2),
  LONG(3),
  FLOAT(4),
  DOUBLE(5),

  LIST(6),
  MAP(7),

  ANY(8),

  DOUBLE_ARRAY(9),
  INT_ARRAY(10),
  LONG_ARRAY(11),
  FLOAT_ARRAY(12),
  BOOLEAN_ARRAY(13),
  BYTE_ARRAY(14),

  RECORD(15),

  JS_VALUE(16),
  JS_OBJECT(17),

  UNIT(18),

  SHARED_OBJECT(19),

  TUPLE(20),
  UNION(21),

  /** A union the codes are already inside of, followed by how many unions out it is. */
  UNION_REF(22),

  /**
   * A JavaScript function handed to Kotlin as a lambda. The head code is followed by a suspend
   * flag, the parameter count, each parameter's codes and the return type's codes.
   */
  FUNCTION(23);

  /** [code], with the [NULLABLE] flag when [isNullable]: the head code of a value of this kind. */
  fun nullableCode(isNullable: Boolean = true): Int =
    if (isNullable) {
      code or NULLABLE
    } else {
      code
    }

  override fun toString(): String = name.lowercase()

  companion object {
    /** Marks a head code nullable. MUST stay in sync with `kNullableFlag` in `CppType.h`. */
    const val NULLABLE = 0x100

    /** Marks a head code as riding the binary buffer. MUST stay in sync with `kUsesBufferFlag`. */
    const val USES_BUFFER = 0x200
  }
}
