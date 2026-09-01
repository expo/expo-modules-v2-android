// DUMP_IR

// MODULE: producer
// FILE: producer.kt
package io.github.expo.modules.v2.testdata.producer

import io.github.expo.modules.v2.annotations.JS
import io.github.expo.modules.v2.annotations.Record
import io.github.expo.modules.v2.modules.Module

@Record
data class Shared(val n: Int, val label: String?)

/**
 * The registration and every trampoline land in this module's class file, so the consumer below
 * registers it without a plugin run of its own — the same property the record half relies on.
 */
@JS(name = "Producer")
object ProducerModule : Module() {
    @JS
    fun bump(value: Shared): Shared = Shared(value.n + 1, value.label)

    @JS
    fun name(prefix: String): String = "$prefix!"

    @JS
    var count: Int = 1
}

// MODULE: consumer(producer)
// FILE: consumer.kt
package io.github.expo.modules.v2.testdata.consumer

import io.github.expo.modules.v2.annotations.JS
import io.github.expo.modules.v2.modules.Module
import io.github.expo.modules.v2.testdata.producer.ProducerModule
import io.github.expo.modules.v2.testdata.producer.Shared

/** A module in this module whose signature names a record from the other one. */
@JS(name = "Consumer")
object ConsumerModule : Module() {
    @JS
    fun forward(value: Shared): Shared = ProducerModule.bump(value)
}

private fun descriptorOf(owner: Class<*>, name: String): String {
    val method = owner.declaredMethods.firstOrNull { it.name == name } ?: return "<absent>"
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

    // The producer's generated members are ordinary compiled code here.
    val define = ProducerModule::class.java
        .getDeclaredMethod("define\$ExpoModulesV2", Class.forName("io.github.expo.modules.v2.modules.ModuleBuilder"))
    if (define.returnType != String::class.java) return "producer define: ${define.returnType}"

    val producer = mapOf(
        "bump$suffix" to "(I)I",
        // a String result takes a slot: AUTO prefers one on the way out
        "name$suffix" to "(I)Ljava/lang/String;",
        "getCount" to "()I",
        "setCount" to "(I)V",
    )
    for ((name, want) in producer) {
        val got = descriptorOf(ProducerModule::class.java, name)
        if (got != want) return "producer $name: expected $want, got $got"
    }

    // The consumer's own trampoline names a record it did not declare, and buffers it just the same.
    val got = descriptorOf(ConsumerModule::class.java, "forward$suffix")
    if (got != "(I)I") return "consumer forward: expected (I)I, got $got"

    // Calling across the boundary still works through the plain Kotlin methods.
    val bumped = ProducerModule.bump(Shared(1, "a"))
    if (bumped != Shared(2, "a")) return "bump gave $bumped"

    return "OK"
}
