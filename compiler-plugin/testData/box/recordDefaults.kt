// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.codecFor

/**
 * Defaults are inbound only: `encode` always writes every field, and `decode` gates each defaulted
 * field on `RecordReader.readIsPresent()`. `tags` is mandatory and sits first so an absent optional
 * field cannot shift the cursor of the fields after it.
 */
@Record
data class Opts(
    val tags: List<String>,
    val count: Int = 5,
    val label: String? = "x",
    val derived: Int = count + 1,
    val trailing: List<Int> = listOf(9),
)

fun box(): String {
    val codec = codecFor<Opts>()

    val optionality = codec.schema.fields.map { "${it.name}=${it.isOptional}" }
    val expected = listOf("tags=false", "count=true", "label=true", "derived=true", "trailing=true")
    if (optionality != expected) return "Fail: optionality is $optionality"

    // An absent key falls back to the Kotlin default.
    val defaults = codec.fromMap(mapOf("tags" to listOf("a")))
    if (defaults != Opts(listOf("a"))) return "Fail: defaults gave $defaults"
    if (defaults.derived != 6) return "Fail: derived default gave ${defaults.derived}"
    if (defaults.trailing != listOf(9)) return "Fail: trailing default gave ${defaults.trailing}"

    // A present key wins, and a default that names an earlier parameter reads that parameter.
    val partial = codec.fromMap(mapOf("tags" to listOf("a"), "count" to 41))
    if (partial.count != 41) return "Fail: count gave ${partial.count}"
    if (partial.derived != 42) return "Fail: derived gave ${partial.derived}"

    // An explicit null on an optional nullable field is not the default.
    val nulled = codec.fromMap(mapOf("tags" to listOf("a"), "label" to null))
    if (nulled.label != null) return "Fail: explicit null gave ${nulled.label}"

    // The map writer always writes every key, so its own reader sees them all present.
    val full = Opts(listOf("a", "b"), 1, null, 2, listOf(3))
    if (codec.fromMap(codec.toMap(full)) != full) return "Fail: symmetric round trip failed"
    if (codec.toMap(full).keys != setOf("tags", "count", "label", "derived", "trailing")) {
        return "Fail: keys are ${codec.toMap(full).keys}"
    }

    return "OK"
}
