// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.SharedRef

/**
 * The other export base. A `@ExpoSharedObject` class declares the same shapes a module does and fills
 * the same builder, so the plugin treats the two alike from `define$ExpoModulesV2` down. The base
 * class itself is added by the plugin, so the class body below names only `@ExpoSharedObject`.
 */
@ExpoSharedObject
class Player : SharedObject() {
    @JS
    fun play(): Int = 1

    @JS
    var volume: Double = 1.0

    // A String argument still buffers, so this one keeps its trampoline.
    @JS
    fun rename(name: String): String = name
}

@ExpoSharedObject(name = "Renamed")
class Speaker : SharedObject() {
    @JS
    fun mute() = Unit
}

/** A `SharedRef` declares its own base, so the plugin adds nothing to the supertype list. */
@ExpoSharedObject
class TextRef(text: StringBuilder) : SharedRef<StringBuilder>(text) {
    @JS
    fun length(): Int = ref.length
}

/**
 * The two ways a class reaches a module. `Nested` is found because it lives inside the module —
 * nesting already says which module owns it — and `Listed` has to be named, being declared outside.
 */
@ExpoSharedObject
class Listed(private val n: Int) : SharedObject() {
    @JS
    fun value(): Int = n
}

@ExpoModule(classes = [Listed::class])
class Owner : Module() {
    @ExpoSharedObject
    class Nested(private val text: String) : SharedObject() {
        @JS
        fun render(): String = text
    }

    /** A private sole constructor: nothing for `new` to reach, so no class object is built. */
    @ExpoSharedObject
    class NotConstructable private constructor() : SharedObject() {
        @JS
        fun name(): String = "hidden"
    }
}

@ExpoModule
class Players : Module() {
    @JS
    fun create(): Player = Player()

    // A shared object is a reference: it stays in its JNI slot, needs no conversion, and therefore
    // needs no trampoline. The slot's descriptor names the *declared* class, because that is what
    // the JVM method signature says.
    @JS
    fun volumeOf(player: Player): Double = player.volume

    @JS
    fun maybe(player: Player?): Int = if (player == null) 0 else 1
}

private fun descriptor(type: Class<*>): String = when {
    type == Int::class.javaPrimitiveType -> "I"
    type == Double::class.javaPrimitiveType -> "D"
    type == Void.TYPE -> "V"
    type.isArray -> "[" + descriptor(type.componentType)
    else -> "L" + type.name.replace('.', '/') + ";"
}

private fun descriptorOf(owner: Class<*>, name: String): String {
    val method = owner.declaredMethods.firstOrNull { it.name == name } ?: return "<absent>"
    val parameters = method.parameterTypes.joinToString("") { descriptor(it) }
    return "($parameters)${descriptor(method.returnType)}"
}

fun box(): String {
    val suffix = "__trampoline\$ExpoModulesV2"
    val player = "Lio/github/expo/modules/v2/testdata/Player;"

    // `ModuleBuilder`'s constructor is internal, so what each base puts *into* it is pinned by the
    // generated-code dump beside this file and by :api's GeneratedSharedObjectTest. What only a real
    // compilation can show is checked here: that both bases got the hook, and the JVM signatures.
    for (owner in listOf(Player::class.java, Speaker::class.java, Players::class.java)) {
        if (descriptorOf(owner, "define\$ExpoModulesV2") == "<absent>") {
            return "${owner.simpleName}: no generated define\$ExpoModulesV2"
        }
    }

    // The plugin puts the base class in place of `Any`, so an annotated class *is* a shared object
    // even though it never named one.
    val base = io.github.expo.modules.v2.SharedObject::class.java
    for (owner in listOf(Player::class.java, Speaker::class.java, Listed::class.java, TextRef::class.java)) {
        if (!base.isAssignableFrom(owner)) {
            return "${owner.simpleName}: not a ExpoSharedObject"
        }
    }
    if (TextRef::class.java.superclass != SharedRef::class.java) {
        return "TextRef: declared base replaced by ${TextRef::class.java.superclass}"
    }

    val expected = mapOf(
        // No conversion and nothing to buffer either way, so the bridge calls these directly.
        Players::class.java to mapOf(
            "volumeOf$suffix" to "<absent>",
            "maybe$suffix" to "<absent>",
            "create$suffix" to "<absent>",
        ),
        // A buffered String argument keeps its trampoline, even on a shared-object class. The
        // String *result* still crosses in its slot — see TransportPolicy.prefersBuffer.
        Player::class.java to mapOf(
            "rename$suffix" to "(I)Ljava/lang/String;",
            "play$suffix" to "<absent>",
        ),
    )
    for ((owner, methods) in expected) {
        for ((name, want) in methods) {
            val got = descriptorOf(owner, name)
            if (got != want) return "${owner.simpleName}.$name: expected $want, got $got"
        }
    }

    // Both discovery routes reach `define$ExpoModulesV2`, and a nested class with no constructor
    // JavaScript can call is left out of it. What each route emits is pinned by the generated-code dump
    // beside this file; here we only check the classes themselves compiled as shared objects.
    for (owner in listOf(Listed::class.java, Owner.Nested::class.java, Owner.NotConstructable::class.java)) {
        if (descriptorOf(owner, "define\$ExpoModulesV2") == "<absent>") {
            return "${owner.simpleName}: no generated define\$ExpoModulesV2"
        }
    }

    // The declared class, not the base: the bridge resolves the method by this signature.
    if (descriptorOf(Players::class.java, "volumeOf") != "($player)D") {
        return "volumeOf: ${descriptorOf(Players::class.java, "volumeOf")}"
    }
    if (descriptorOf(Players::class.java, "create") != "()$player") {
        return "create: ${descriptorOf(Players::class.java, "create")}"
    }

    return "OK"
}
