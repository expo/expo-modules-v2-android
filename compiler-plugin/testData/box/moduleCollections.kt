// FIR_IDENTICAL

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
import io.github.expo.modules.v2.unions.UnionValue
import java.net.URL

/**
 * `Collection` crosses as a JavaScript array, like `List`, but is never handed straight through: a
 * result may be a `Set` or a map's `values`, which the JNI `List` slot cannot hold. The `Buffer.NO`
 * exports keep their slot, so their trampolines can be invoked without a runtime.
 */
@ExpoModule
object Collections : Module() {
    private val byName = linkedMapOf("a" to 1, "b" to 2)

    @JS
    @BufferMode(Buffer.NO)
    fun total(values: Collection<Int>): Int = values.sum()

    @JS
    @BufferMode(Buffer.NO)
    fun values(): Collection<Int> = byName.values

    @JS
    @BufferMode(Buffer.NO)
    fun unique(values: MutableCollection<String>): Collection<String> = values.toCollection(linkedSetOf())

    @JS
    @BufferMode(Buffer.NO)
    fun hosts(urls: Collection<URL>?): Collection<String>? = urls?.map { it.host }

    @JS
    fun buffered(values: Collection<Long>): Collection<Long> = values

    // Nested: each inner collection is converted on its own, so a Set inside reaches the bridge as
    // a List.
    @JS
    @BufferMode(Buffer.NO)
    fun groups(): List<Collection<Int>> = listOf(linkedSetOf(3, 1), byName.values)

    @JS
    @BufferMode(Buffer.NO)
    fun sizes(groups: List<Collection<Int>>): List<Int> = groups.map { it.size }

    @JS
    @BufferMode(Buffer.NO)
    fun byKey(): Map<String, Collection<Int>> = linkedMapOf("set" to linkedSetOf(5, 4), "values" to byName.values)

    @JS
    @BufferMode(Buffer.NO)
    fun counts(groups: Map<String, Collection<Int>>): Map<String, Int> = groups.mapValues { it.value.size }

    @JS
    @BufferMode(Buffer.NO)
    fun shelf(books: Collection<Book>): Collection<Book> = books.toCollection(linkedSetOf())

    @JS
    var tags: Collection<String> = linkedSetOf("x", "y")
}

@Record
data class Book(val id: Int, val title: String)

// A Collection variant told apart from a String one by the kind of JavaScript value.
@Union
sealed interface Tags {
    @JvmInline
    value class Many(val values: Collection<String>) : Tags

    @JvmInline
    value class One(val value: String) : Tags
}

@Record
class Shelf(
    val titles: Collection<String>,
    val ids: MutableCollection<Long>,
    val maybe: Collection<Int>?,
)

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
    assertExports(Collections) {
        function("total") {
            args(slot<Collection<Int>>())
            returns(slot<Int>())
            trampoline()
        }
        function("values") {
            returns(slot<Collection<Int>>())
            trampoline()
        }
        function("unique") {
            args(slot<MutableCollection<String>>())
            returns(slot<Collection<String>>())
            trampoline()
        }
        function("hosts") {
            args(slot<Collection<URL>?>())
            returns(slot<Collection<String>?>())
            trampoline()
        }
        function("buffered") {
            args(buffered<Collection<Long>>())
            returns(buffered<Collection<Long>>())
            trampoline()
        }
        function("groups") {
            returns(slot<List<Collection<Int>>>())
            trampoline()
        }
        function("sizes") {
            args(slot<List<Collection<Int>>>())
            returns(slot<List<Int>>())
            trampoline()
        }
        function("byKey") {
            returns(slot<Map<String, Collection<Int>>>())
            trampoline()
        }
        function("counts") {
            args(slot<Map<String, Collection<Int>>>())
            returns(slot<Map<String, Int>>())
            trampoline()
        }
        function("shelf") {
            args(slot<Collection<Book>>())
            returns(slot<Collection<Book>>())
            trampoline()
        }
        property("tags", buffered<Collection<String>>()) {
            trampoline()
            mutable(buffered<Collection<String>>())
        }
    }

    val suffix = "__trampoline\$ExpoModulesV2"
    val owner = Collections::class.java

    // The slot carries the bridge List both ways.
    val total = owner.getDeclaredMethod("total$suffix", List::class.java)
        .invoke(Collections, listOf(1, 2, 3)) as Int
    if (total != 6) return "total: expected 6, got $total"

    // A map's values is not a List: it has to be copied into one on the way out.
    val values = owner.getDeclaredMethod("values$suffix").invoke(Collections)
    if (values !is List<*> || values != listOf(1, 2)) return "values: got $values (${values?.javaClass})"

    val unique = owner.getDeclaredMethod("unique$suffix", List::class.java)
        .invoke(Collections, listOf("a", "b", "a"))
    if (unique !is List<*> || unique != listOf("a", "b")) return "unique: got $unique (${unique?.javaClass})"

    val hosts = owner.getDeclaredMethod("hosts$suffix", List::class.java)
        .invoke(Collections, listOf("https://expo.dev/a", "https://docs.expo.dev/b"))
    if (hosts != listOf("expo.dev", "docs.expo.dev")) return "hosts: got $hosts"
    val noHosts = owner.getDeclaredMethod("hosts$suffix", List::class.java).invoke(Collections, null)
    if (noHosts != null) return "hosts(null): got $noHosts"

    // A Set or a map's values nested in a List or a Map still reaches the bridge as a List.
    val groups = owner.getDeclaredMethod("groups$suffix").invoke(Collections) as List<*>
    if (groups != listOf(listOf(3, 1), listOf(1, 2)) || !groups.all { it is List<*> }) {
        return "groups: got $groups (${groups.map { it?.javaClass }})"
    }
    val sizes = owner.getDeclaredMethod("sizes$suffix", List::class.java)
        .invoke(Collections, listOf(listOf(1, 2), listOf<Int>()))
    if (sizes != listOf(2, 0)) return "sizes: got $sizes"

    val byKey = owner.getDeclaredMethod("byKey$suffix").invoke(Collections) as Map<*, *>
    if (byKey != mapOf("set" to listOf(5, 4), "values" to listOf(1, 2)) || !byKey.values.all { it is List<*> }) {
        return "byKey: got $byKey (${byKey.values.map { it?.javaClass }})"
    }
    val counts = owner.getDeclaredMethod("counts$suffix", Map::class.java)
        .invoke(Collections, mapOf("a" to listOf(1, 2, 3)))
    if (counts != mapOf("a" to 3)) return "counts: got $counts"

    // Records inside a Collection cross as maps, each converted.
    val books = listOf(mapOf("id" to 1, "title" to "Dune"), mapOf("id" to 2, "title" to "Emma"))
    val shelved = owner.getDeclaredMethod("shelf$suffix", List::class.java).invoke(Collections, books)
    if (shelved !is List<*> || shelved != books) return "shelf: got $shelved (${shelved?.javaClass})"

    // A union variant holding a Collection decodes into it and encodes out of any collection.
    val tags = TypeDescriptor.Simple(Tags::class.java, false).anyConverter
    val many = tags.toJni(Tags.Many(linkedSetOf("b", "a"))) as UnionValue
    if (many.index != 0 || many.payload != listOf("b", "a") || many.payload !is List<*>) {
        return "Fail: Many crossed as ${many.index}/${many.payload} (${many.payload?.javaClass})"
    }
    val one = tags.toJni(Tags.One("x")) as UnionValue
    if (one.index != 1 || one.payload != "x") return "Fail: One crossed as ${one.index}/${one.payload}"
    val decodedMany = tags.fromJni(UnionValue(0, listOf("c", "d")))
    if (decodedMany !is Tags.Many || decodedMany.values.toList() != listOf("c", "d")) {
        return "Fail: Many round trip gave $decodedMany"
    }
    if (tags.fromJni(UnionValue(1, "y")) != Tags.One("y")) return "Fail: One round trip"

    // Buffered both ways: one payloadLength in, the result's length out.
    val buffered = descriptorOf(owner, "buffered$suffix")
    if (buffered != "(I)I") return "buffered: got $buffered"

    val codec = codecFor<Shelf>()
    val fieldTypes = codec.schema.fields.map { "${it.name}=${it.type}" }
    val expected = listOf(
        "titles=Collection<String>",
        "ids=Collection<Long>",
        "maybe=Collection<Integer>?",
    )
    if (fieldTypes != expected) return "Fail: descriptors are $fieldTypes"

    val shelf = Shelf(titles = linkedSetOf("b", "a"), ids = ArrayDeque(listOf(7L, 8L)), maybe = null)
    val map = codec.toMap(shelf)
    if (map["titles"] != listOf("b", "a")) return "Fail: titles crossed as ${map["titles"]}"
    if (map["ids"] != listOf(7L, 8L)) return "Fail: ids crossed as ${map["ids"]}"
    if (map["maybe"] != null) return "Fail: maybe crossed as ${map["maybe"]}"

    val back = codec.fromMap(map)
    if (back.titles.toList() != listOf("b", "a")) return "Fail: titles gave ${back.titles}"
    if (back.ids.toList() != listOf(7L, 8L)) return "Fail: ids gave ${back.ids}"
    if (back.maybe != null) return "Fail: maybe gave ${back.maybe}"

    return "OK"
}
