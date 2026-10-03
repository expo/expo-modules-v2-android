// FIR_IDENTICAL
// DUMP_IR

// MODULE: producer
// FILE: producer.kt
package io.github.expo.modules.v2.testdata.producer

// Compiled in its own module, so the consumer sees it only through its class files.
enum class Level(val value: Int) {
    LOW(1),
    HIGH(2),
}

enum class Mode(val value: String) {
    FAST("fast"),
    SLOW("slow"),
}

// MODULE: consumer(producer)
// FILE: consumer.kt
package io.github.expo.modules.v2.testdata.consumer

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.testdata.producer.Level
import io.github.expo.modules.v2.testdata.producer.Mode

/** An `Int` enum from another module still crosses as an `Int`, and a `String` one as a `String`. */
@ExpoModule
object Levels : Module() {
    @JS
    fun echo(level: Level): Level = level

    @JS
    @BufferMode(Buffer.NO)
    fun maybe(level: Level?): Level? = level

    @JS
    @BufferMode(Buffer.NO)
    fun mode(mode: Mode): Mode = mode
}

private fun descriptorOf(name: String): String {
    val method = Levels::class.java.declaredMethods.firstOrNull { it.name == name }
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
        "echo$suffix" to "(I)I",
        "maybe$suffix" to "(Ljava/lang/Integer;)Ljava/lang/Integer;",
        "mode$suffix" to "(Ljava/lang/String;)Ljava/lang/String;",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(name)
        if (got != want) return "$name: expected $want, got $got"
    }
    return "OK"
}
