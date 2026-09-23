// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

/**
 * The whole module generator adds members in the backend only, so this pins two things the rest
 * depends on: that the JVM backend emits a member the frontend never declared, and that the emitted
 * member really overrides `Module.define$ExpoModulesV2`.
 *
 * Every value here is a non-null scalar, an `Any` or a JSI-free array — all of which keep their JNI
 * slot — so no trampoline is involved and the bridge calls each method directly.
 */
@ExpoModule
object Direct : Module() {
    @JS
    fun add(a: Int, b: Int): Int = a + b

    @JS
    fun scale(values: DoubleArray, by: Double): Double = values.sum() * by

    @JS
    fun identity(value: Any?): Any? = value

    @JS(name = "renamed")
    fun original(flag: Boolean): Boolean = !flag

    @JS
    fun clear() = Unit

    @JS
    val answer: Int = 42

    @JS
    var count: Int = 1

    // Not annotated, so not exported.
    fun internalHelper(): Int = 0
}

@ExpoModule(name = "Instance")
class DirectInstance : Module() {
    @JS
    fun echo(value: Long): Long = value
}

fun box(): String {
    // The generated override is reached through the base declaration, so plain virtual dispatch.
    val method = Module::class.java.getMethod("define\$ExpoModulesV2", Class.forName("io.github.expo.modules.v2.modules.ModuleBuilder"))
    if (method.declaringClass != Module::class.java) return "base lookup: ${method.declaringClass}"

    val generated = Direct::class.java.getDeclaredMethod(
        "define\$ExpoModulesV2",
        Class.forName("io.github.expo.modules.v2.modules.ModuleBuilder"),
    )
    if (generated.returnType != String::class.java) {
        return "expected a String return, got ${generated.returnType}"
    }

    // A direct export is invoked on the user's own method, so its signature must be untouched.
    Direct::class.java.getDeclaredMethod("add", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    Direct::class.java.getDeclaredMethod("clear")
    Direct::class.java.getDeclaredMethod("getAnswer")
    Direct::class.java.getDeclaredMethod("setCount", Int::class.javaPrimitiveType)
    DirectInstance::class.java.getDeclaredMethod("echo", Long::class.javaPrimitiveType)

    if (Direct::class.java.declaredMethods.any { it.name.contains("__trampoline") }) {
        return "a direct-only module should have no trampolines"
    }

    return "OK"
}
