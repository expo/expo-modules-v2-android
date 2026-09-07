// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.codecFor

@Record
class Samples(
    val ints: IntArray,
    val longs: LongArray,
    val floats: FloatArray,
    val doubles: DoubleArray,
    val bools: BooleanArray,
    val bytes: ByteArray,
    val maybe: IntArray?,
)

fun box(): String {
    val codec = codecFor<Samples>()

    val fieldTypes = codec.schema.fields.map { "${it.name}=${it.type}" }
    val expected = listOf(
        "ints=int_array", "longs=long_array", "floats=float_array", "doubles=double_array",
        "bools=boolean_array", "bytes=byte_array", "maybe=int_array?",
    )
    if (fieldTypes != expected) return "Fail: descriptors are $fieldTypes"

    val samples = Samples(
        intArrayOf(1, 2), longArrayOf(3L), floatArrayOf(4.5f), doubleArrayOf(5.5),
        booleanArrayOf(true, false), byteArrayOf(6), null,
    )
    val back = codec.fromMap(codec.toMap(samples))
    if (!back.ints.contentEquals(samples.ints)) return "Fail: ints gave ${back.ints.toList()}"
    if (!back.longs.contentEquals(samples.longs)) return "Fail: longs gave ${back.longs.toList()}"
    if (!back.floats.contentEquals(samples.floats)) return "Fail: floats gave ${back.floats.toList()}"
    if (!back.doubles.contentEquals(samples.doubles)) return "Fail: doubles gave ${back.doubles.toList()}"
    if (!back.bools.contentEquals(samples.bools)) return "Fail: bools gave ${back.bools.toList()}"
    if (!back.bytes.contentEquals(samples.bytes)) return "Fail: bytes gave ${back.bytes.toList()}"
    if (back.maybe != null) return "Fail: maybe gave ${back.maybe?.toList()}"

    return "OK"
}
