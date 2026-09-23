// FIR_IDENTICAL
// DUMP_IR

// MODULE: producer
// FILE: producer.kt
package io.github.expo.modules.v2.testdata.producer

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

/**
 * Compiling an `@ExpoModule` also emits a hint interface into `io.github.expo.modules.v2.hints`,
 * so a later compilation can find this module on its classpath without being told about it.
 */
@ExpoModule
object First : Module() {
    @JS
    fun one(): Int = 1
}

/** A module that is a class rather than an object, and one whose JS name differs from the class. */
@ExpoModule(name = "Renamed")
class Second : Module()

object Outer {
    /** A nested module gets a hint too: the hint's name is derived from the full class name. */
    @ExpoModule
    object Nested : Module()
}

// MODULE: consumer(producer)
// FILE: consumer.kt
package io.github.expo.modules.v2.testdata.consumer

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.discoveredExpoModules
import io.github.expo.modules.v2.testdata.producer.First
import io.github.expo.modules.v2.testdata.producer.Outer
import io.github.expo.modules.v2.testdata.producer.Second

/** A module declared in the discovering compilation itself is part of the list as well. */
@ExpoModule
object Local : Module()

/**
 * The plugin rewrites this call into the list itself: the hints on the classpath plus every
 * @ExpoModule of this module. Without the rewrite the :api body throws.
 */
private fun discovered(): List<Class<out Module>> = discoveredExpoModules()

fun box(): String {
    val modules = discovered()

    val expected = listOf<Class<out Module>>(
        First::class.java,
        Second::class.java,
        Outer.Nested::class.java,
        Local::class.java,
    )
    val missing = expected.filterNot { it in modules }
    if (missing.isNotEmpty()) return "missing: ${missing.map { it.name }} in ${modules.map { it.name }}"

    val unexpected = modules.filterNot { it in expected }
    if (unexpected.isNotEmpty()) return "unexpected: ${unexpected.map { it.name }}"

    if (modules.size != modules.distinct().size) return "duplicates in ${modules.map { it.name }}"

    // Each module on the classpath came through one hint interface with one member naming it.
    val hints = modules
        .filter { it != Local::class.java }
        .map { module -> Class.forName("io.github.expo.modules.v2.hints." + hintNameOf(module)) }
    for (hint in hints) {
        if (!hint.isInterface) return "${hint.name} is not an interface"
        val members = hint.declaredMethods
        if (members.size != 1 || members[0].name != "module") {
            return "${hint.name} declares ${members.map { it.name }}"
        }
        if (members[0].returnType !in modules) return "${hint.name} names ${members[0].returnType}"
    }

    return "OK"
}

/** Mirrors the compiler plugin's mangling: the dotted class name plus a hash of it. */
private fun hintNameOf(module: Class<*>): String {
    val fqName = module.name.replace('$', '.')
    return fqName.replace('.', '_') + "_" + String.format("%08x", fqName.hashCode())
}
