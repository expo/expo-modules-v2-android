// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

/** A module with nothing to export: `define` still has to name it, with an empty argument vararg. */
@ExpoModule
object Empty : Module()

/** The argument-count boundary the bridge enforces: Trampoline.MAX_ARGUMENTS is 8. */
@ExpoModule
object Arity : Module() {
    @JS
    fun none(): Int = 0

    @JS
    fun one(a: Int): Int = a

    @JS
    fun eight(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int): Int =
        a + b + c + d + e + f + g + h

    // Eight buffered arguments: every one drops its parameter, leaving a single payloadLength.
    @JS
    fun eightBuffered(
        a: String,
        b: String,
        c: String,
        d: String,
        e: String,
        f: String,
        g: String,
        h: String,
    ): Int = a.length + b.length + c.length + d.length + e.length + f.length + g.length + h.length
}

/**
 * A private member is exported too. `GetMethodID` resolves a private method, and unlike `internal`
 * the JVM does not mangle its name — which is why the checker rejects only `internal`.
 */
@ExpoModule
object Visibility : Module() {
    @JS
    private fun hidden(a: Int): Int = a * 2

    @JS
    fun visible(a: Int): Int = a + 1
}

/**
 * A module that does not extend `Module` directly. The generator converts the inherited fake override
 * of `define$ExpoModulesV2` in place, so it has to find one in the leaf class even when the member is
 * inherited through an intermediate class.
 */
abstract class BaseModule : Module() {
    fun inherited(): Int = 1
}

@ExpoModule
object Derived : BaseModule() {
    @JS
    fun own(a: Int): Int = a + 1
}

/** A companion object and a nested class are not exports, and must not confuse discovery. */
@ExpoModule
class WithNested : Module() {
    @JS
    fun value(): Int = 1

    class Nested {
        fun ignored(): Int = 0
    }

    companion object {
        const val CONSTANT: Int = 5
    }
}

private fun names(owner: Class<*>): Set<String> =
    owner.declaredMethods.map { it.name }.toSet()

fun box(): String {
    val builder = Class.forName("io.github.expo.modules.v2.modules.ModuleBuilder")

    // An export-free module still overrides define, and still names itself.
    Empty::class.java.getDeclaredMethod("define\$ExpoModulesV2", builder)
    if (names(Empty::class.java).any { it.contains("__trampoline") }) {
        return "an empty module should generate no trampolines"
    }

    // Unboxed Int arguments need no trampoline at any arity.
    for (name in listOf("none", "one", "eight")) {
        if (names(Arity::class.java).contains("${name}__trampoline\$ExpoModulesV2")) {
            return "$name should not have a trampoline"
        }
    }

    // Eight buffered Strings collapse to one payloadLength parameter.
    val eightBuffered = Arity::class.java
        .getDeclaredMethod("eightBuffered__trampoline\$ExpoModulesV2", Int::class.javaPrimitiveType)
    if (eightBuffered.returnType != Int::class.javaPrimitiveType) {
        return "eightBuffered: expected an int return, got ${eightBuffered.returnType}"
    }

    // A private export is a real declared method, reachable exactly as the bridge reaches it.
    val hidden = Visibility::class.java.getDeclaredMethod("hidden", Int::class.javaPrimitiveType)
    hidden.isAccessible = true
    val doubled = hidden.invoke(Visibility, 21) as Int
    if (doubled != 42) return "hidden(21) gave $doubled"
    if (names(Visibility::class.java).none { it == "visible" }) return "visible is missing"

    // An inherited `define$ExpoModulesV2` is still overridden in the leaf class.
    val derived = Derived::class.java.getDeclaredMethod("define\$ExpoModulesV2", builder)
    if (derived.returnType != String::class.java) return "derived define: ${derived.returnType}"
    if (Derived.own(1) != 2) return "derived own() is broken"

    // A companion object and a nested class contribute no exports.
    WithNested::class.java.getDeclaredMethod("define\$ExpoModulesV2", builder)
    if (names(WithNested::class.java).any { it.contains("__trampoline") }) {
        return "WithNested should generate no trampolines"
    }

    return "OK"
}
