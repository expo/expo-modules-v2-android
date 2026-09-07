// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.Record as RecordMarker
import io.github.expo.modules.v2.records.codecFor

@Record
data class Point(val x: Double, val y: Double)

fun box(): String {
    val point = Point(1.5, -2.5)

    if (point !is RecordMarker) return "Fail: Point did not get the Record supertype"

    val codec = codecFor<Point>()
    if (codec.recordClass != Point::class.java) return "Fail: recordClass is ${codec.recordClass}"

    val schema = codec.schema
    if (schema.name != "Point") return "Fail: schema name is ${schema.name}"
    if (!schema.bufferSafe) return "Fail: schema is not bufferSafe"
    if (schema.fields.map { it.name } != listOf("x", "y")) {
        return "Fail: fields are ${schema.fields.map { it.name }}"
    }

    val map = codec.toMap(point)
    if (map != mapOf("x" to 1.5, "y" to -2.5)) return "Fail: toMap gave $map"
    if (codec.fromMap(map) != point) return "Fail: fromMap gave ${codec.fromMap(map)}"

    return "OK"
}
