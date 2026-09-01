// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.annotations.Record
import io.github.expo.modules.v2.records.codecFor

@Record
data class Inner(val n: Int)

@Record
data class Outer(val inner: Inner, val list: List<Inner>, val maybe: Inner?)

// A cycle: each side names the other with a bare class literal, which resolves the class without
// initializing it. RecordRegistry.typeFor forces that init on the miss.
@Record
data class LoopA(val n: Int, val b: LoopB?)

@Record
data class LoopB(val n: Int, val a: LoopA?)

fun box(): String {
    val codec = codecFor<Outer>()
    val outer = Outer(Inner(1), listOf(Inner(2), Inner(3)), null)

    val map = codec.toMap(outer)
    if (map["inner"] !is Map<*, *>) return "Fail: nested record did not decompose, got ${map["inner"]}"
    val list = map["list"] as List<*>
    if (list.any { it !is Map<*, *> }) return "Fail: list elements did not decompose, got $list"
    if (map["maybe"] != null) return "Fail: maybe is ${map["maybe"]}"
    if (codec.fromMap(map) != outer) return "Fail: round trip gave ${codec.fromMap(map)}"

    // The cycle resolves from either end.
    val loop = LoopA(1, LoopB(2, LoopA(3, null)))
    val loopCodec = codecFor<LoopA>()
    if (loopCodec.fromMap(loopCodec.toMap(loop)) != loop) return "Fail: cycle round trip failed"

    return "OK"
}
