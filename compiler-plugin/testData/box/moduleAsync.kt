// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.async.Promise
import io.github.expo.modules.v2.Module
import kotlinx.coroutines.delay

/**
 * A `suspend` export's generated trampoline.
 *
 * The shape it pins: `Unit` return whatever the export declares, a trailing `Promise` after
 * `payloadLength`, buffered arguments still decoded eagerly and synchronously, and the user's body
 * wrapped in a `suspend` lambda handed to `Promise.launch`. The result reaches JavaScript through
 * the promise, so it never appears in the JVM signature.
 */
@ExpoModule
class AsyncShapes : Module() {
    // No buffered value anywhere: slots only, so the trampoline is (I, Promise)V.
    @JS
    @BufferMode(Buffer.NO)
    suspend fun scale(value: Int): Int {
        delay(1)
        return value * 2
    }

    // A buffered argument AND a buffered result: (I, Promise)V, where the leading I is payloadLength.
    @JS
    suspend fun greet(name: String): String = "Hello, $name!"

    // A buffered argument beside a slot argument, so payloadLength sits between them and the
    // Promise still comes last: (I, I, Promise)V.
    @JS
    suspend fun repeat(@BufferMode(Buffer.NO) times: Int, value: String): String = value.repeat(times)

    // Nothing to resolve with. The trampoline still returns Unit, not the export's Unit.
    @JS
    suspend fun clear(): Unit = delay(1)
}

private const val TRAMPOLINE = "__trampoline\$ExpoModulesV2"

fun box(): String {
    val clazz = AsyncShapes::class.java
    val int = Int::class.javaPrimitiveType
    val promise = Class.forName("io.github.expo.modules.v2.async.Promise")

    // Every suspend export gets a trampoline, even one whose values would all cross unchanged.
    val scale = clazz.getDeclaredMethod("scale$TRAMPOLINE", int, promise)
    if (scale.returnType != Void.TYPE) return "scale returns ${scale.returnType}"

    // The buffered argument collapses into payloadLength, and the Promise follows it.
    val greet = clazz.getDeclaredMethod("greet$TRAMPOLINE", int, promise)
    if (greet.returnType != Void.TYPE) return "greet returns ${greet.returnType}"

    // Slot argument, then payloadLength, then the Promise.
    clazz.getDeclaredMethod("repeat$TRAMPOLINE", int, int, promise)

    val clear = clazz.getDeclaredMethod("clear$TRAMPOLINE", promise)
    if (clear.returnType != Void.TYPE) return "clear returns ${clear.returnType}"

    // The user's own methods keep their suspend signature: a trailing Continuation, untouched.
    val continuation = Class.forName("kotlin.coroutines.Continuation")
    clazz.getDeclaredMethod("scale", int, continuation)
    clazz.getDeclaredMethod("greet", String::class.java, continuation)

    // A synchronous export in the same module must be unaffected: no Promise, no trampoline.
    if (clazz.declaredMethods.any { it.name == "scale" && it.parameterTypes.contains(promise) }) {
        return "the user's own method was rewritten"
    }

    return "OK"
}
