// FIR_IDENTICAL

package io.github.expo.modules.v2.testdata

import io.github.expo.kolibri.binary.BinaryBuffer
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
import java.util.UUID

/** `UUID` crosses as its `String`, like `URI`: buffered unless told otherwise. */
@ExpoModule
object Uuids : Module() {
    @JS
    fun echo(id: UUID): UUID = id

    @JS
    fun maybe(id: UUID?): UUID? = id

    @JS
    @BufferMode(Buffer.NO)
    fun version(id: UUID): Int = id.version()

    @JS
    @BufferMode(Buffer.NO)
    fun named(seed: String): UUID = UUID.nameUUIDFromBytes(seed.toByteArray())
}

@Record(bufferSafe = false)
class Tagged(val id: UUID, val parent: UUID?)

// Both variants cross as a string: a string UUID.fromString refuses goes on to Text.
@Union
sealed interface Key {
    @JvmInline
    value class Id(val id: UUID) : Key

    @JvmInline
    value class Text(val text: String) : Key
}

private fun descriptorOf(owner: Class<*>, name: String): String {
    val method = owner.declaredMethods.firstOrNull { it.name == name }
        ?: return "<no method $name>"
    val parameters = method.parameterTypes.joinToString("") { descriptor(it) }
    return "($parameters)${descriptor(method.returnType)}"
}

private fun descriptor(type: Class<*>): String = when {
    type == Int::class.javaPrimitiveType -> "I"
    type == Void.TYPE -> "V"
    else -> "L" + type.name.replace('.', '/') + ";"
}

fun box(): String {
    assertExports(Uuids) {
        function("echo") {
            args(buffered<UUID>())
            returns(buffered<UUID>())
            trampoline()
        }
        function("maybe") {
            args(buffered<UUID?>())
            returns(buffered<UUID?>())
            trampoline()
        }
        function("version") {
            args(slot<UUID>())
            returns(slot<Int>())
            trampoline()
        }
        function("named") {
            args(slot<String>())
            returns(slot<UUID>())
            trampoline()
        }
    }

    val suffix = "__trampoline\$ExpoModulesV2"
    val expected = mapOf(
        // both sides ride the payload: one payloadLength in, the result's length out
        "echo$suffix" to "(I)I",
        "maybe$suffix" to "(I)I",
        // Buffer.NO keeps the bridge String in a slot each way
        "version$suffix" to "(Ljava/lang/String;)I",
        "named$suffix" to "(Ljava/lang/String;)Ljava/lang/String;",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(Uuids::class.java, name)
        if (got != want) return "$name: expected $want, got $got"
    }

    // A slot trampoline converts through the registry: the String in, the String out.
    val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
    val version = Uuids::class.java
        .getDeclaredMethod("version$suffix", String::class.java)
        .invoke(Uuids, id) as Int
    if (version != 4) return "version: expected 4, got $version"
    val named = Uuids::class.java
        .getDeclaredMethod("named$suffix", String::class.java)
        .invoke(Uuids, "expo") as String
    if (named != UUID.nameUUIDFromBytes("expo".toByteArray()).toString()) return "named: got $named"

    // A record field.
    val codec = codecFor<Tagged>()
    val fieldTypes = codec.schema.fields.map { "${it.name}=${it.type}" }
    if (fieldTypes != listOf("id=UUID", "parent=UUID?")) return "Fail: descriptors are $fieldTypes"
    val map = codec.toMap(Tagged(UUID.fromString(id), null))
    if (map["id"] != id || map["parent"] != null) return "Fail: record crossed as $map"
    val back = codec.fromMap(mapOf("id" to id, "parent" to id))
    if (back.id != UUID.fromString(id) || back.parent != UUID.fromString(id)) return "Fail: record read back wrong"

    // A union whose variants share the string bridge.
    val key = TypeDescriptor.Simple(Key::class.java, false).anyConverter
    val sent = key.toJni(Key.Id(UUID.fromString(id)))
    if (sent != id) return "Fail: Id crossed as $sent"
    val picked = key.fromJni(id)
    if (picked != Key.Id(UUID.fromString(id))) return "Fail: a UUID string picked $picked"
    val fallback = key.fromJni("hello")
    if (fallback != Key.Text("hello")) return "Fail: a plain string picked $fallback"
    // UUID.fromString alone would take this one as a made-up UUID.
    val loose = key.fromJni("1-2-3-4-5")
    if (loose != Key.Text("1-2-3-4-5")) return "Fail: a loose UUID string picked $loose"

    // The buffer path: the union shares the string bridge, so the payload is the bare string, and a
    // refused one is read again by the next variant.
    val cases = mapOf(
        id to Key.Id(UUID.fromString(id)),
        "hello" to Key.Text("hello"),
        "1-2-3-4-5" to Key.Text("1-2-3-4-5"),
    )
    for ((sentString, want) in cases) {
        val buffer = BinaryBuffer.allocate(256)
        val writer = buffer.duplicateView()
        writer.putString(sentString)
        val view = buffer.duplicateView()
        view.limit = writer.position
        val got = key.readFromBuffer(view)
        if (got != want) return "Fail: '$sentString' on the buffer picked $got"
    }
    val buffer = BinaryBuffer.allocate(256)
    val writer = buffer.duplicateView()
    key.writeToBuffer(writer, Key.Id(UUID.fromString(id)))
    val view = buffer.duplicateView()
    view.limit = writer.position
    val roundTrip = key.readFromBuffer(view)
    if (roundTrip != Key.Id(UUID.fromString(id))) return "Fail: Id round trip on the buffer gave $roundTrip"

    return "OK"
}
