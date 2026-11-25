// FIR_IDENTICAL
@file:OptIn(ExperimentalUuidApi::class)

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.Union
import io.github.expo.modules.v2.boxsupport.assertExports
import io.github.expo.modules.v2.boxsupport.buffered
import io.github.expo.modules.v2.boxsupport.slot
import io.github.expo.modules.v2.records.codecFor
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** `kotlin.uuid.Uuid` crosses as its `String`, like `java.net.URI`: buffered unless told otherwise. */
@ExpoModule
object KotlinUuids : Module() {
    @JS
    fun echo(id: Uuid): Uuid = id

    @JS
    fun maybe(id: Uuid?): Uuid? = id

    @JS
    @BufferMode(Buffer.NO)
    fun next(id: Uuid): Uuid = id.toLongs { msb, lsb -> Uuid.fromLongs(msb, lsb + 1) }
}

@Record
data class Tagged(val id: Uuid, val parent: Uuid?)

// Both variants cross as a string. Uuid refuses one that is no UUID, which then goes to Name.
@Union
sealed interface IdOrName {
    @JvmInline
    value class Id(val id: Uuid) : IdOrName

    @JvmInline
    value class Name(val name: String) : IdOrName
}

private const val FIRST = "550e8400-e29b-41d4-a716-446655440000"
private const val SECOND = "550e8400-e29b-41d4-a716-446655440001"

fun box(): String {
    assertExports(KotlinUuids) {
        function("echo") {
            args(buffered<Uuid>())
            returns(buffered<Uuid>())
            trampoline()
        }
        function("maybe") {
            args(buffered<Uuid?>())
            returns(buffered<Uuid?>())
            trampoline()
        }
        function("next") {
            args(slot<Uuid>())
            returns(slot<Uuid>())
            trampoline()
        }
    }

    // Buffer.NO: the trampoline takes and returns the bridge String, converting both ways.
    val next = KotlinUuids::class.java
        .getDeclaredMethod("next__trampoline\$ExpoModulesV2", String::class.java)
        .invoke(KotlinUuids, FIRST)
    if (next != SECOND) return "Fail: next gave $next"

    val codec = codecFor<Tagged>()
    val fieldTypes = codec.schema.fields.map { "${it.name}=${it.type}" }
    if (fieldTypes != listOf("id=Uuid", "parent=Uuid?")) return "Fail: descriptors are $fieldTypes"
    val tagged = Tagged(Uuid.parse(FIRST), null)
    val map = codec.toMap(tagged)
    if (map["id"] != FIRST) return "Fail: id crossed as ${map["id"]}"
    if (map["parent"] != null) return "Fail: parent crossed as ${map["parent"]}"
    if (codec.fromMap(map) != tagged) return "Fail: record round trip gave ${codec.fromMap(map)}"

    val idOrName = TypeDescriptor.Simple(IdOrName::class.java, false).anyConverter
    if (idOrName.toJni(IdOrName.Id(Uuid.parse(FIRST))) != FIRST) return "Fail: Id did not cross as its string"
    if (idOrName.fromJni(FIRST) != IdOrName.Id(Uuid.parse(FIRST))) return "Fail: a UUID string was not an Id"
    if (idOrName.fromJni("expo") != IdOrName.Name("expo")) return "Fail: a plain string was not a Name"

    return "OK"
}
