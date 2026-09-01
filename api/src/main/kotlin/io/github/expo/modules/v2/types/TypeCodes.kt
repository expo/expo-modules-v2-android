package io.github.expo.modules.v2.types

@JvmInline
value class TypeCodes(val values: IntArray) {
  val size: Int get() = values.size

  val isEmpty: Boolean get() = values.isEmpty()

  operator fun get(index: Int): Int = values[index]

  fun kindAt(index: Int): CppType = CppType(values[index] and CppType.HEAD_FLAGS.inv())

  val head: CppType get() = kindAt(0)

  val isNullable: Boolean get() = values[0] and CppType.NULLABLE != 0

  val usesBuffer: Boolean get() = values[0] and CppType.USES_BUFFER != 0

  fun withBufferFlag() = TypeCodes(
    values.copyOf().also { it[0] = it[0] or CppType.USES_BUFFER }
  )

  fun contentEquals(other: TypeCodes): Boolean = values.contentEquals(other.values)

  override fun toString(): String = values.joinToString(", ", "[", "]")

  inline fun forEach(block: (Int) -> Unit) {
    values.forEach(block)
  }

  companion object {
    fun of(vararg codes: Int): TypeCodes = TypeCodes(codes)
  }
}
