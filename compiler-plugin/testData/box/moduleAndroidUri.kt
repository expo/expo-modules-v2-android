// FIR_IDENTICAL

// FILE: Uri.kt
// A stand-in for the Android framework class: box tests run on the plain JVM, and only the class's
// name decides how it crosses.
package android.net

class Uri private constructor(private val value: String) {
    override fun toString(): String = value

    companion object {
        @JvmStatic
        fun parse(value: String): Uri = Uri(value)
    }
}

// FILE: module.kt
package io.github.expo.modules.v2.testdata

import android.net.Uri
import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

/**
 * `android.net.Uri` crosses as its `String`, like `java.net.URI`: buffered unless told otherwise.
 *
 * Only the generated trampolines are checked. `assertExports` runs `define$ExpoModulesV2`, which
 * resolves each converter, and the one for `Uri` is registered by `:react` - this runs against
 * `:api`'s desktop table, which has none.
 */
@ExpoModule
object AndroidUris : Module() {
    @JS
    fun normalize(uri: Uri): Uri = uri

    @JS
    fun maybe(uri: Uri?): Uri? = uri

    @JS
    @BufferMode(Buffer.NO)
    fun inSlot(uri: Uri): Uri = uri
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
        // both sides ride the payload: one payloadLength in, the result's length out
        "normalize$suffix" to "(I)I",
        "maybe$suffix" to "(I)I",
        // Buffer.NO keeps the bridge String in a slot each way
        "inSlot$suffix" to "(Ljava/lang/String;)Ljava/lang/String;",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(AndroidUris::class.java, name)
        if (got != want) return "$name: expected $want, got $got"
    }

    return "OK"
}
