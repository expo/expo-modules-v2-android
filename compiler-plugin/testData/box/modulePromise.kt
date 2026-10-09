// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.async.Promise
import io.github.expo.modules.v2.async.resolve

/**
 * An export that returns a `Promise<T>` instead of suspending.
 *
 * The shape it pins: the trampoline signature of a `suspend` export - `Unit` return, a trailing
 * `PromiseHandle` after `payloadLength` - but the body is called synchronously and the promise it
 * returns goes to `PromiseHandle.subscribeTo`, with the descriptor of `T` rather than of `Promise<T>`.
 */
@ExpoModule
class PromiseShapes : Module() {
    // No buffered value anywhere: slots only, so the trampoline is (I, PromiseHandle)V.
    @JS
    @BufferMode(Buffer.NO)
    fun scale(value: Int): Promise<Int> = Promise<Int>().apply { resolve(value * 2) }

    // A buffered argument: (I, PromiseHandle)V, where the I is payloadLength.
    @JS
    fun greet(name: String): Promise<String> {
        val promise = Promise<String>()
        promise.resolve("Hello, $name!")
        return promise
    }

    // Nothing to resolve with: the descriptor is Unit's.
    @JS
    fun clear(): Promise<Unit> = Promise<Unit>().apply { resolve() }

    // A nullable result: the promise may resolve with null, the promise itself never is.
    @JS
    fun maybe(): Promise<String?> = Promise<String?>().apply { resolve(null) }
}

private const val TRAMPOLINE = "__trampoline\$ExpoModulesV2"

fun box(): String {
    val clazz = PromiseShapes::class.java
    val int = Int::class.javaPrimitiveType
    val handle = Class.forName("io.github.expo.modules.v2.async.PromiseHandle")
    val promise = Class.forName("io.github.expo.modules.v2.async.Promise")

    // The same shape native calls for a `suspend` export.
    val scale = clazz.getDeclaredMethod("scale$TRAMPOLINE", int, handle)
    if (scale.returnType != Void.TYPE) return "scale returns ${scale.returnType}"

    val greet = clazz.getDeclaredMethod("greet$TRAMPOLINE", int, handle)
    if (greet.returnType != Void.TYPE) return "greet returns ${greet.returnType}"

    clazz.getDeclaredMethod("clear$TRAMPOLINE", handle)
    clazz.getDeclaredMethod("maybe$TRAMPOLINE", handle)

    // The user's own methods keep their signature: no handle, and the Promise still returned.
    val own = clazz.getDeclaredMethod("scale", int)
    if (own.returnType != promise) return "scale returns ${own.returnType}"

    return "OK"
}
