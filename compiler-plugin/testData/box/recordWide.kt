// FIR_IDENTICAL
// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.codecFor

/**
 * 42 fields, the width of `TextFieldColorsRecord` — the widest record in the expo repo. One
 * `TypeDescriptor.Int` singleton read per field, so the codec carries no descriptor fields at all.
 */
@Record
class Wide(
    val field0: Int,
    val field1: Int,
    val field2: Int,
    val field3: Int,
    val field4: Int,
    val field5: Int,
    val field6: Int,
    val field7: Int,
    val field8: Int,
    val field9: Int,
    val field10: Int,
    val field11: Int,
    val field12: Int,
    val field13: Int,
    val field14: Int,
    val field15: Int,
    val field16: Int,
    val field17: Int,
    val field18: Int,
    val field19: Int,
    val field20: Int,
    val field21: Int,
    val field22: Int,
    val field23: Int,
    val field24: Int,
    val field25: Int,
    val field26: Int,
    val field27: Int,
    val field28: Int,
    val field29: Int,
    val field30: Int,
    val field31: Int,
    val field32: Int,
    val field33: Int,
    val field34: Int,
    val field35: Int,
    val field36: Int,
    val field37: Int,
    val field38: Int,
    val field39: Int,
    val field40: Int,
    val field41: Int,
)

fun box(): String {
    val codec = codecFor<Wide>()
    if (codec.schema.fields.size != 42) return "Fail: ${codec.schema.fields.size} fields"
    if (codec.schema.fields[41].name != "field41") return "Fail: last field is ${codec.schema.fields[41].name}"

    val wide = Wide(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41)
    val map = codec.toMap(wide)
    if (map.size != 42) return "Fail: toMap gave ${map.size} keys"
    if (map["field41"] != 41) return "Fail: field41 crossed as ${map["field41"]}"
    // The map writer and reader share one moving cursor over schema.fields, so a mislabelled field
    // shows up here as a shifted value.
    if (codec.fromMap(map).field7 != 7) return "Fail: field7 gave ${codec.fromMap(map).field7}"

    return "OK"
}
