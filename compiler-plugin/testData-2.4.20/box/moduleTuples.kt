// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.Module

@Record
data class Point(val x: Double, val y: Double)

// A record field is described the same way, element by element.
@Record
data class Segment(val ends: Pair<Point, Point>, val label: Triple<String, Int, Boolean>?)

/**
 * `Pair` and `Triple` are described as tuples of their element types, and follow the container
 * rules: they buffer when every element can, and otherwise cross as a JList in a slot.
 */
@ExpoModule
object Tuples : Module() {
    @JS
    fun swap(pair: Pair<Int, String>): Pair<String, Int> = Pair(pair.second, pair.first)

    // Nullability lives on the element and on the tuple itself.
    @JS
    fun triple(value: Triple<String, Int?, Boolean>?): Int = value?.first?.length ?: 0

    // Tuples nest inside containers, and containers inside tuples.
    @JS
    fun zip(pairs: List<Pair<String, Int>>): Pair<List<String>, List<Int>> = pairs.unzip()

    @JS
    fun echoSegment(segment: Segment): Segment = segment

    // A JSI handle has no binary encoding, so the tuple keeps a JList slot.
    @JS
    fun handles(pair: Pair<String, JavaScriptValue>): Int = pair.first.length

    // Buffer.NO keeps both sides in slots, but a tuple still needs converting.
    @JS
    @BufferMode(Buffer.NO)
    fun swapDirect(pair: Pair<Int, String>): Pair<String, Int> = Pair(pair.second, pair.first)
}

private fun descriptorOf(name: String): String {
    val method = Tuples::class.java.declaredMethods.firstOrNull { it.name == name }
        ?: return "<absent>"
    val parameters = method.parameterTypes.joinToString("") { descriptor(it) }
    return "($parameters)${descriptor(method.returnType)}"
}

private fun descriptor(type: Class<*>): String = when {
    type == Int::class.javaPrimitiveType -> "I"
    type == Long::class.javaPrimitiveType -> "J"
    type == Float::class.javaPrimitiveType -> "F"
    type == Double::class.javaPrimitiveType -> "D"
    type == Boolean::class.javaPrimitiveType -> "Z"
    type == Void.TYPE -> "V"
    type.isArray -> "[" + descriptor(type.componentType)
    else -> "L" + type.name.replace('.', '/') + ";"
}

fun box(): String {
    val suffix = "__trampoline\$ExpoModulesV2"
    val expected = mapOf(
        // a buffered tuple both ways
        "swap$suffix" to "(I)I",
        // a buffered nullable tuple and an unboxed Int result
        "triple$suffix" to "(I)I",
        "zip$suffix" to "(I)I",
        // a buffer-safe record with tuple fields rides the payload both ways
        "echoSegment$suffix" to "(I)I",
        // the handle keeps the tuple in a JList slot
        "handles$suffix" to "(Ljava/util/List;)I",
        // Buffer.NO: JList slots both ways
        "swapDirect$suffix" to "(Ljava/util/List;)Ljava/util/List;",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(name)
        if (got != want) return "$name: expected $want, got $got"
    }
    return "OK"
}
