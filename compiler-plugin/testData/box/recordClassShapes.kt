// FIR_IDENTICAL
// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.records.Record as RecordMarker
import io.github.expo.modules.v2.records.codecFor

// A plain class, not a data class: the field rule is the primary constructor either way.
@Record
class Plain(val a: Int, val b: String)

// A private class still gets a codec; the nested codec class is private too.
@Record
private class Hidden(val a: Int)

// A record already carrying the marker interface must not get it twice.
@Record
class Explicit(val a: Int) : RecordMarker

// A user companion the plugin has to extend rather than create.
@Record
class WithCompanion(val a: Int) {
    companion object {
        const val LABEL = "with-companion"

        fun make(a: Int) = WithCompanion(a)
    }
}

class Host {
    // A nested record: its codec is nested one level deeper again.
    @Record
    class Nested(val a: Int)
}

// A record with no fields at all: `schema` must pass an empty vararg, never a null.
@Record
class Empty

fun box(): String {
    if (codecFor<Plain>().schema.fields.map { it.name } != listOf("a", "b")) {
        return "Fail: Plain fields are ${codecFor<Plain>().schema.fields.map { it.name }}"
    }
    if (codecFor<Plain>().fromMap(mapOf("a" to 1, "b" to "s")).b != "s") return "Fail: Plain round trip"

    if (codecFor<Hidden>().schema.name != "Hidden") return "Fail: Hidden has no codec"

    if (Explicit(1) !is RecordMarker) return "Fail: Explicit lost the marker"
    if (codecFor<Explicit>().schema.fields.size != 1) return "Fail: Explicit fields"

    if (WithCompanion.LABEL != "with-companion") return "Fail: user companion member disappeared"
    if (WithCompanion.make(2).a != 2) return "Fail: user companion function disappeared"
    if (codecFor<WithCompanion>().fromMap(mapOf("a" to 3)).a != 3) return "Fail: WithCompanion round trip"

    if (codecFor<Host.Nested>().schema.name != "Nested") return "Fail: nested record has no codec"

    val empty = codecFor<Empty>()
    if (empty.schema.fields.isNotEmpty()) return "Fail: Empty has fields ${empty.schema.fields}"
    if (empty.toMap(Empty()).isNotEmpty()) return "Fail: Empty toMap is ${empty.toMap(Empty())}"

    return "OK"
}
