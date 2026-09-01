// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.annotations.Buffer
import io.github.expo.modules.v2.annotations.JS
import io.github.expo.modules.v2.modules.Module
import java.net.URI
import java.net.URL
import kotlin.time.Duration

/**
 * Converted values that keep their JNI slot, so the trampolines here can be invoked without a
 * runtime: `Duration` bridges as an unboxed `Double`, and `Buffer.NO` keeps the `URL`'s `String`
 * bridge in a slot too.
 */
@JS
object Converted : Module() {
    @JS
    fun total(a: Duration, b: Duration): Duration = a + b

    @JS(buffer = Buffer.NO)
    fun host(url: URL): String = url.host

    @JS(buffer = Buffer.NO)
    fun firstHost(urls: List<URL>): String = urls.first().host
}

/**
 * Buffered values, whose bodies need the shared binary buffer — so only their signatures are checked
 * here. That is the part the bridge depends on: it resolves each trampoline by name and JNI
 * descriptor, and a buffered argument contributes no parameter while adding one trailing
 * `payloadLength`.
 */
@JS
object Buffered : Module() {
    @JS
    fun greet(name: String): String = "Hello, $name!"

    @JS
    fun sum(values: List<Int>): Int = values.sum()

    @JS
    fun describe(prefix: String, count: Int): String = "$prefix$count"

    @JS
    fun makeNames(count: Int): List<String> = List(count) { "n$it" }

    @JS
    fun scale(values: DoubleArray): Double = values.sum()

    @JS
    var motto: String = "carpe diem"

    @JS
    val isReady: Boolean = true
}

private fun descriptorOf(owner: Class<*>, name: String): String {
    val method = owner.declaredMethods.firstOrNull { it.name == name }
        ?: return "<no method $name>"
    val parameters = method.parameterTypes.joinToString("") { descriptor(it) }
    return "($parameters)${descriptor(method.returnType)}"
}

private fun descriptor(type: Class<*>): String = when {
    type == Int::class.javaPrimitiveType -> "I"
    type == Double::class.javaPrimitiveType -> "D"
    type == Boolean::class.javaPrimitiveType -> "Z"
    type == Void.TYPE -> "V"
    type.isArray -> "[" + descriptor(type.componentType)
    else -> "L" + type.name.replace('.', '/') + ";"
}

fun box(): String {
    val suffix = "__trampoline\$ExpoModulesV2"

    // A converted value in a JNI slot: the trampoline takes and returns the bridge type.
    val total = Converted::class.java
        .getDeclaredMethod("total$suffix", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        .invoke(Converted, 1.5, 2.5) as Double
    if (total != 4.0) return "total: expected 4.0, got $total"

    val host = Converted::class.java
        .getDeclaredMethod("host$suffix", String::class.java)
        .invoke(Converted, "https://expo.dev/path") as String
    if (host != "expo.dev") return "host: expected expo.dev, got $host"

    // Element-wise conversion inside a container that kept its slot.
    val firstHost = Converted::class.java
        .getDeclaredMethod("firstHost$suffix", List::class.java)
        .invoke(Converted, listOf("https://a.example/x", "https://b.example/y")) as String
    if (firstHost != "a.example") return "firstHost: expected a.example, got $firstHost"

    // Buffered signatures. A buffered argument drops its parameter and adds one payloadLength.
    val expected = mapOf(
        // the argument rides the payload; the String result takes a slot, which AUTO prefers on the
        // way out — a jstring straight into jsi::String skips the buffer's fixed setup
        "greet$suffix" to "(I)Ljava/lang/String;",
        // buffered argument, unboxed Int result
        "sum$suffix" to "(I)I",
        // count keeps its slot, prefix rides the payload, String result in a slot
        "describe$suffix" to "(II)Ljava/lang/String;",
        // a List result is still buffered: only String changes direction
        "makeNames$suffix" to "(I)I",
        // a primitive array is faster in its own slot, so this needs no trampoline at all
        "scale$suffix" to "<no method scale$suffix>",
        // each accessor is planned on its own: a String is written in on the payload and read back
        // out of a slot, so the getter is a plain forwarding method
        "getMotto$suffix" to "()Ljava/lang/String;",
        "setMotto$suffix" to "(I)V",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(Buffered::class.java, name)
        if (got != want) return "$name: expected $want, got $got"
    }

    // A non-null Boolean keeps its slot, so `isReady` is exported through its own accessor.
    if (Buffered::class.java.declaredMethods.any { it.name.startsWith("getReady") }) {
        return "isReady should not have a trampoline"
    }
    Buffered::class.java.getDeclaredMethod("isReady")

    return "OK"
}
