// FIR_IDENTICAL
// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.codecFor

// Nullable primitives box: the descriptor is the boxed CommonDescriptors singleton, and encode /
// decode pick the `write(Int?)` / `readIntOrNull()` overloads.
@Record
data class Boxes(
    val i: Int?,
    val l: Long?,
    val f: Float?,
    val d: Double?,
    val b: Boolean?,
    val s: String?,
)

fun box(): String {
    val codec = codecFor<Boxes>()

    val full = Boxes(1, 2L, 3.5f, 4.5, true, "s")
    val fullMap = codec.toMap(full)
    if (fullMap != mapOf<String, Any?>("i" to 1, "l" to 2L, "f" to 3.5f, "d" to 4.5, "b" to true, "s" to "s")) {
        return "Fail: toMap gave $fullMap"
    }
    if (codec.fromMap(fullMap) != full) return "Fail: fromMap gave ${codec.fromMap(fullMap)}"

    val empty = Boxes(null, null, null, null, null, null)
    val emptyMap = codec.toMap(empty)
    if (emptyMap.size != 6) return "Fail: nulls dropped keys, got $emptyMap"
    if (emptyMap.values.any { it != null }) return "Fail: nulls did not cross, got $emptyMap"
    if (codec.fromMap(emptyMap) != empty) return "Fail: fromMap gave ${codec.fromMap(emptyMap)}"

    return "OK"
}
