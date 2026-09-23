// FIR_IDENTICAL
// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.codecFor

// Containers box their element descriptors: `Parametrized.params` is `Array<out ObjectLike>`, and
// the primitive descriptor objects are not ObjectLike.
@Record
class Bag(
    val tags: List<String>,
    val counts: List<Int>,
    val maybes: List<String?>,
    val ids: Set<Long>,
    val meta: Map<String, Any?>,
    val names: Array<String>,
    val nested: List<List<Int>>,
    val absent: List<String>?,
    val absentNames: Array<String>?,
    val mutableTags: MutableList<String>,
    val mutableMeta: MutableMap<String, Int>,
)

fun box(): String {
    val codec = codecFor<Bag>()

    val fieldTypes = codec.schema.fields.map { "${it.name}=${it.type}" }
    val expected = listOf(
        "tags=List<String>",
        "counts=List<Integer>",
        "maybes=List<String?>",
        "ids=Set<Long>",
        "meta=Map<String, Object?>",
        "names=String[]<String>",
        "nested=List<List<Integer>>",
        "absent=List<String>?",
        "absentNames=String[]<String>?",
        "mutableTags=List<String>",
        "mutableMeta=Map<String, Integer>",
    )
    if (fieldTypes != expected) return "Fail: descriptors are $fieldTypes"

    val bag = Bag(
        tags = listOf("a", "b"),
        counts = listOf(1, 2),
        maybes = listOf("x", null),
        ids = setOf(7L),
        meta = mapOf("k" to 1, "n" to null),
        names = arrayOf("n"),
        nested = listOf(listOf(1), listOf(2, 3)),
        absent = null,
        absentNames = null,
        mutableTags = mutableListOf("m"),
        mutableMeta = mutableMapOf("k" to 1),
    )
    val map = codec.toMap(bag)
    if (map["tags"] != listOf("a", "b")) return "Fail: tags is ${map["tags"]}"
    if (map["absent"] != null) return "Fail: absent is ${map["absent"]}"

    val back = codec.fromMap(map)
    if (back.tags != bag.tags) return "Fail: tags round trip gave ${back.tags}"
    if (back.counts != bag.counts) return "Fail: counts round trip gave ${back.counts}"
    if (back.maybes != bag.maybes) return "Fail: maybes round trip gave ${back.maybes}"
    if (back.ids != bag.ids) return "Fail: ids round trip gave ${back.ids}"
    if (back.meta != bag.meta) return "Fail: meta round trip gave ${back.meta}"
    if (back.names.toList() != bag.names.toList()) return "Fail: names round trip gave ${back.names.toList()}"
    if (back.nested != bag.nested) return "Fail: nested round trip gave ${back.nested}"
    if (back.absent != null) return "Fail: absent round trip gave ${back.absent}"
    if (back.absentNames != null) return "Fail: absentNames round trip gave ${back.absentNames?.toList()}"
    if (back.mutableTags != bag.mutableTags) return "Fail: mutableTags round trip gave ${back.mutableTags}"
    if (back.mutableMeta != bag.mutableMeta) return "Fail: mutableMeta round trip gave ${back.mutableMeta}"

    return "OK"
}
