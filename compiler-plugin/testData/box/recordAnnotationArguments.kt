// FIR_IDENTICAL
// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.codecFor

@Record
data class Defaults(val x: Double, val y: Double)

@Record(bufferSafe = false)
data class Unsafe(val payload: Any?)

fun box(): String {
    // A record has no name in JavaScript, so the schema's is always the class's own - it exists for
    // the error messages the encoder builds.
    if (codecFor<Defaults>().schema.name != "Defaults") {
        return "Fail: name is ${codecFor<Defaults>().schema.name}"
    }
    if (!codecFor<Defaults>().schema.bufferSafe) return "Fail: Defaults lost its default bufferSafe"

    val unsafe = codecFor<Unsafe>().schema
    if (unsafe.bufferSafe) return "Fail: Unsafe is still bufferSafe"
    if (unsafe.name != "Unsafe") return "Fail: Unsafe name is ${unsafe.name}"

    return "OK"
}
