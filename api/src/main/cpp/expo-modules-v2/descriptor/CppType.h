#pragma once

namespace expo::modules::v2 {
  enum class CppType : int {
    BOOLEAN = 0,
    STRING = 1,
    INT = 2,
    LONG = 3,
    FLOAT = 4,
    DOUBLE = 5,
    LIST = 6,
    MAP = 7,
    ANY = 8,

    DOUBLE_ARRAY = 9,
    INT_ARRAY = 10,
    LONG_ARRAY = 11,
    FLOAT_ARRAY = 12,
    BOOLEAN_ARRAY = 13,
    BYTE_ARRAY = 14,

    RECORD = 15,

    JS_VALUE = 16,
    JS_OBJECT = 17,

    UNIT = 18,

    BOX_INT = 19,
    BOX_BOOLEAN = 20,
    BOX_LONG = 21,
    BOX_FLOAT = 22,
    BOX_DOUBLE = 23,
  };

  /**
   * Marks a reference/container head nullable. MUST stay in sync with Kotlin's `CppType.NULLABLE`.
   */
  inline constexpr int kNullableFlag = 0x100;

  /**
   * Marks a head code as riding the shared binary buffer (Kotlin `AnyType.buffered()`).
   * Head-position only - never on nested element codes or record fields.
   */
  inline constexpr int kUsesBufferFlag = 0x200;

  /**
   * All flag bits a head code can carry; mask with `~kHeadFlagsMask` to get the kind code.
   */
  inline constexpr int kHeadFlagsMask = kNullableFlag | kUsesBufferFlag;
} // namespace expo::modules::v2
