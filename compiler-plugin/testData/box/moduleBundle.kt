// FIR_IDENTICAL

// FILE: Bundle.kt
// A stand-in for the Android framework class: box tests run on the plain JVM, and only the class's
// name decides how it crosses.
package android.os

class Bundle

// FILE: module.kt
package io.github.expo.modules.v2.testdata

import android.os.Bundle
import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record

@Record
class Extras(val bundle: Bundle, val label: String)

/**
 * `android.os.Bundle` crosses as a `Map<String, Any?>`, and like `Any` it keeps its JNI slot unless
 * `Buffer.YES` moves it onto the payload. It never passes through, so every export has a trampoline.
 *
 * Only the generated trampolines are checked. `assertExports` runs `define$ExpoModulesV2`, which
 * resolves each converter, and the one for `Bundle` is registered by `:react` - this runs against
 * `:api`'s desktop table, which has none.
 */
@ExpoModule
object Bundles : Module() {
    @JS
    fun echo(bundle: Bundle): Bundle = bundle

    @JS
    fun maybe(bundle: Bundle?): Bundle? = bundle

    @JS
    @BufferMode(Buffer.YES)
    fun buffered(bundle: Bundle): Bundle = bundle

    @JS
    fun all(bundles: List<Bundle>): Map<String, Bundle> = emptyMap()

    // A Bundle field makes the record unsafe for the buffer, as an `Any` field does.
    @JS
    fun extras(extras: Extras): Int = 0
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
    val suffix = "__trampoline\$ExpoModulesV2"
    val expected = mapOf(
        // the bridge Map in a slot each way
        "echo$suffix" to "(Ljava/util/Map;)Ljava/util/Map;",
        "maybe$suffix" to "(Ljava/util/Map;)Ljava/util/Map;",
        // Buffer.YES: one payloadLength in, the result's length out
        "buffered$suffix" to "(I)I",
        // containers of Bundles keep their slot too: their elements are dynamic
        "all$suffix" to "(Ljava/util/List;)Ljava/util/Map;",
        "extras$suffix" to "(Ljava/util/Map;)I",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(Bundles::class.java, name)
        if (got != want) return "$name: expected $want, got $got"
    }

    return "OK"
}
