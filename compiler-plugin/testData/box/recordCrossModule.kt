// DUMP_IR
// FIR_DUMP

// MODULE: producer
// FILE: producer.kt
package expo.modules.v2.testdata.producer

import expo.modules.v2.annotations.Record

// The supertype and the whole codec are written into this module's class files, so the consumer
// below needs no plugin run of its own to see `Shared` as a record.
@Record
data class Shared(val n: Int, val label: String?)

// MODULE: consumer(producer)
// FILE: consumer.kt
package expo.modules.v2.testdata.consumer

import expo.modules.v2.annotations.Record
import expo.modules.v2.records.Record as RecordMarker
import expo.modules.v2.records.codecFor
import expo.modules.v2.testdata.producer.Shared

// A record in this module whose field type is a record from the other one.
@Record
data class Holder(val shared: Shared, val more: List<Shared>)

fun box(): String {
    if (Shared(1, "a") !is RecordMarker) return "Fail: the Record supertype did not cross modules"

    val shared = codecFor<Shared>()
    if (shared.schema.fields.map { it.name } != listOf("n", "label")) {
        return "Fail: Shared fields are ${shared.schema.fields.map { it.name }}"
    }
    if (shared.fromMap(shared.toMap(Shared(1, "a"))) != Shared(1, "a")) {
        return "Fail: Shared round trip"
    }

    val holder = Holder(Shared(2, null), listOf(Shared(3, "c")))
    val codec = codecFor<Holder>()
    val map = codec.toMap(holder)
    if (map["shared"] !is Map<*, *>) return "Fail: the cross-module record did not decompose"
    if (codec.fromMap(map) != holder) return "Fail: Holder round trip gave ${codec.fromMap(map)}"

    return "OK"
}
