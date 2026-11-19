package io.github.expo.modules.v2.types

import io.github.expo.kolibri.binary.BinaryBuffer

/**
 * Collects type codes. Every converter writes its own through one, and one writer can be [reset]
 * and used again for the next type, so building many types does not allocate an array for each.
 *
 * It tracks the unions it is inside of, so a union met again inside itself - `List<Tree>` in
 * `Tree` - is written as a [CppType.UNION_REF] back to it instead of being spelled out forever.
 * Codes written with nothing around them hold no reference past their own outermost union.
 */
internal class TypeCodesWriter {
  private var values = IntArray(16)

  /** How many codes are written. */
  var size = 0
    private set

  private val unions = ArrayList<Any>()

  operator fun get(index: Int): Int = values[index]

  fun add(code: Int) {
    if (size == values.size) {
      values = values.copyOf(size * 2)
    }
    values[size++] = code
  }

  fun add(type: CppType) = add(type.code)

  /** Sets [flags] on the code at [index]: the buffer flag on a head written before. */
  fun addFlags(index: Int, flags: Int) {
    values[index] = values[index] or flags
  }

  /** Overwrites the code at [index]: a count reserved before what it counts was written. */
  fun set(index: Int, code: Int) {
    values[index] = code
  }

  /** `length, byte...`: [value] as UTF-8, one byte per code. */
  fun addString(value: String) {
    val bytes = value.encodeToByteArray()
    add(bytes.size)
    bytes.forEach { add(it.toInt() and 0xFF) }
  }

  /** How many unions out [union] is - 0 for the nearest - or -1 when the codes are not inside it. */
  fun depthOf(union: Any): Int {
    val index = unions.indexOfLast { it === union }
    return if (index < 0) -1 else unions.size - 1 - index
  }

  fun insideUnion(union: Any, block: () -> Unit) {
    unions.add(union)
    try {
      block()
    } finally {
      unions.removeAt(unions.size - 1)
    }
  }

  /** Whether the codes in [from] until [until] equal those in [otherFrom] until [otherUntil]. */
  fun rangeEquals(from: Int, until: Int, otherFrom: Int, otherUntil: Int): Boolean {
    if (until - from != otherUntil - otherFrom) {
      return false
    }
    for (offset in 0 until until - from) {
      if (values[from + offset] != values[otherFrom + offset]) {
        return false
      }
    }
    return true
  }

  /** Forgets every code written, keeping the array for the next type. */
  fun reset() {
    check(unions.isEmpty()) { "Cannot reset a TypeCodesWriter while it writes a union" }
    size = 0
  }

  /** `count, code...` into [buf]: an `intArray` of the module descriptor. */
  fun writeTo(buf: BinaryBuffer) {
    buf.putInt(size)
    for (index in 0 until size) {
      buf.putInt(values[index])
    }
  }

  fun toIntArray(): IntArray = values.copyOf(size)
}
