// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.annotations.JS
import io.github.expo.modules.v2.modules.Module
import io.github.expo.modules.v2.sharedobjects.SharedObject

/**
 * The other `@JS` base. A shared-object class declares the same shapes a module does and fills the
 * same builder, so the plugin treats the two alike from `define$ExpoModulesV2` down.
 */
@JS
class Player : SharedObject() {
    @JS
    fun play(): Int = 1

    @JS
    var volume: Double = 1.0

    // A String argument still buffers, so this one keeps its trampoline.
    @JS
    fun rename(name: String): String = name
}

@JS(name = "Renamed")
class Speaker : SharedObject() {
    @JS
    fun mute() = Unit
}

/**
 * The two ways a class reaches a module. `Nested` is found because it lives inside the module —
 * nesting already says which module owns it — and `Listed` has to be named, being declared outside.
 */
@JS
class Listed @JS constructor(private val n: Int) : SharedObject() {
    @JS
    fun value(): Int = n
}

@JS(classes = [Listed::class])
class Owner : Module() {
    @JS
    class Nested @JS constructor(private val text: String) : SharedObject() {
        @JS
        fun render(): String = text
    }

    /** Nested and shared, but with no constructor for JavaScript, so not constructable there. */
    @JS
    class NotConstructable : SharedObject() {
        @JS
        fun name(): String = "hidden"
    }
}

@JS
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

    // Both discovery routes reach `define$ExpoModulesV2`, and a nested class without an annotated
    // constructor is left out of it. What each route emits is pinned by the generated-code dump
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
