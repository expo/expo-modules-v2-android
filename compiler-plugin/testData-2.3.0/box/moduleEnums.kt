// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

enum class Style(val value: String) {
    LIGHT("light"),
    HEAVY("heavy"),
}

enum class Priority(val value: Int) {
    LOW(0),
    HIGH(10),
}

/**
 * An enum's bridge is another type: it crosses as its `String` or its `Int`. It always needs a
 * conversion, so it always gets a trampoline.
 */
@ExpoModule
object Enums : Module() {
    // An enum buffers like the String it crosses as.
    @JS
    fun echoStyle(style: Style): Style = style

    // Out of the buffer, it is a String slot each way.
    @JS
    @BufferMode(Buffer.NO)
    fun styleSlot(style: Style?): Style? = style

    // An Int enum is an unboxed Int slot, which cannot ride the buffer, like a non-null Int.
    @JS
    fun echoPriority(priority: Priority): Priority = priority

    // A nullable one boxes, so it buffers like an Int? would.
    @JS
    fun maybePriority(priority: Priority?): Priority? = priority

    // Out of the buffer, it is an Integer slot each way.
    @JS
    @BufferMode(Buffer.NO)
    fun priorityNoBuffer(priority: Priority?): Priority? = priority
}

private fun descriptorOf(name: String): String {
    val method = Enums::class.java.declaredMethods.firstOrNull { it.name == name }
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
    val expected = mapOf(
        "echoStyle$suffix" to "(I)I",
        "styleSlot$suffix" to "(Ljava/lang/String;)Ljava/lang/String;",
        "echoPriority$suffix" to "(I)I",
        "maybePriority$suffix" to "(I)I",
        "priorityNoBuffer$suffix" to "(Ljava/lang/Integer;)Ljava/lang/Integer;",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(name)
        if (got != want) return "$name: expected $want, got $got"
    }
    return "OK"
}
