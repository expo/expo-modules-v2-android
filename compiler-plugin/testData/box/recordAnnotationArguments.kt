// DUMP_IR
// FIR_DUMP

package expo.modules.v2.testdata

import expo.modules.v2.annotations.Record
import expo.modules.v2.records.codecFor

@Record(name = "Half")
data class Renamed(val x: Double, val y: Double)

@Record(bufferSafe = false)
data class Unsafe(val payload: Any?)

@Record(name = "Both", bufferSafe = false)
data class BothArguments(val v: String)

fun box(): String {
    if (codecFor<Renamed>().schema.name != "Half") {
        return "Fail: name is ${codecFor<Renamed>().schema.name}"
    }
    if (!codecFor<Renamed>().schema.bufferSafe) return "Fail: Renamed lost its default bufferSafe"

    if (codecFor<Unsafe>().schema.bufferSafe) return "Fail: Unsafe is still bufferSafe"
    if (codecFor<Unsafe>().schema.name != "Unsafe") {
        return "Fail: Unsafe name is ${codecFor<Unsafe>().schema.name}"
    }

    val both = codecFor<BothArguments>().schema
    if (both.name != "Both" || both.bufferSafe) return "Fail: both gave ${both.name}/${both.bufferSafe}"

    return "OK"
}
