// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.TypedArray

/**
 * A typed array's bridge is another type: it crosses as the live `JavaScriptObject` it wraps. It
 * always needs a conversion, so it always gets a trampoline.
 */
@ExpoModule
object TypedArrays : Module() {
    // A typed array is a live handle: a JavaScriptObject slot, never the buffer.
    @JS
    fun byteLength(array: TypedArray): Int = array.byteLength

    @JS
    fun echoArray(array: TypedArray?): TypedArray? = array
}

private fun descriptorOf(name: String): String {
    val method = TypedArrays::class.java.declaredMethods.firstOrNull { it.name == name }
        ?: return "<absent>"
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
    val jsObject = "Lio/github/expo/modules/v2/jsi/JavaScriptObject;"
    val expected = mapOf(
        "byteLength$suffix" to "($jsObject)I",
        "echoArray$suffix" to "($jsObject)$jsObject",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(name)
        if (got != want) return "$name: expected $want, got $got"
    }
    return "OK"
}
