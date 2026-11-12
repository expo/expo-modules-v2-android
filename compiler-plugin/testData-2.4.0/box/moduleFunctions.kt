// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.async.Promise

/**
 * A lambda parameter stands for a JavaScript function.
 *
 * The shape it pins: the parameter keeps a JNI slot of its own (`JavaScriptFunction`), is never
 * buffered, and always gets a trampoline, because the live handle has to be wrapped in the lambda
 * before the user's body sees it. Plain and `suspend` lambdas differ only in the descriptor.
 */
@ExpoModule
class Lambdas : Module() {
    // The lambda is the only argument, so the trampoline is (JavaScriptFunction)V.
    @JS
    fun call(callback: (Int) -> Unit) {
        callback(1)
    }

    // A buffered argument beside a lambda: the lambda keeps its slot, the list collapses into
    // payloadLength - (JavaScriptFunction, I)V.
    @JS
    fun each(values: List<Int>, callback: (Int) -> Unit) {
        values.forEach(callback)
    }

    // A lambda that returns a value, with several parameters and nullable types.
    @JS
    fun transform(callback: (String, Int?) -> String?): String? = callback("x", null)

    // A nullable lambda is a nullable slot.
    @JS
    fun optional(callback: ((Int) -> Unit)?) {
        callback?.invoke(1)
    }

    // A suspend lambda in a suspend export: slot, then the PromiseHandle - (JavaScriptFunction, PromiseHandle)V.
    @JS
    suspend fun later(callback: suspend (Int) -> Int): Int = callback(1)

    // A plain lambda in a suspend export, and a lambda beside a promise return.
    @JS
    suspend fun plainInAsync(callback: (Int) -> Unit) {
        callback(1)
    }

    @JS
    fun promised(callback: suspend () -> String): Promise<Int> = Promise.resolve(1)
}

private const val TRAMPOLINE = "__trampoline\$ExpoModulesV2"

fun box(): String {
    val clazz = Lambdas::class.java
    // Not initialized: JavaScriptFunction is a native object, and the native library is not loaded here.
    val loader = clazz.classLoader
    val handle = Class.forName("io.github.expo.modules.v2.async.PromiseHandle", false, loader)
    val function = Class.forName("io.github.expo.modules.v2.jsi.JavaScriptFunction", false, loader)
    val int = Int::class.javaPrimitiveType

    fun trampoline(name: String) = clazz.declaredMethods.single { it.name == "$name$TRAMPOLINE" }

    val call = trampoline("call")
    if (call.parameterTypes.toList() != listOf(function)) return "call takes ${call.parameterTypes.toList()}"
    if (call.returnType != Void.TYPE) return "call returns ${call.returnType}"

    val each = trampoline("each")
    if (each.parameterTypes.toList() != listOf(function, int)) return "each takes ${each.parameterTypes.toList()}"

    val transform = trampoline("transform")
    if (transform.parameterTypes.toList() != listOf(function)) {
        return "transform takes ${transform.parameterTypes.toList()}"
    }
    if (transform.returnType != String::class.java) return "transform returns ${transform.returnType}"

    val optional = trampoline("optional")
    if (optional.parameterTypes.toList() != listOf(function)) return "optional takes ${optional.parameterTypes.toList()}"

    val later = trampoline("later")
    if (later.parameterTypes.toList() != listOf(function, handle)) return "later takes ${later.parameterTypes.toList()}"
    if (later.returnType != Void.TYPE) return "later returns ${later.returnType}"

    val plainInAsync = trampoline("plainInAsync")
    if (plainInAsync.parameterTypes.toList() != listOf(function, handle)) {
        return "plainInAsync takes ${plainInAsync.parameterTypes.toList()}"
    }

    val promised = trampoline("promised")
    if (promised.parameterTypes.toList() != listOf(function, handle)) {
        return "promised takes ${promised.parameterTypes.toList()}"
    }

    // The user's own members keep the lambda types they declared.
    val function1 = Class.forName("kotlin.jvm.functions.Function1")
    clazz.getDeclaredMethod("call", function1)
    val continuation = Class.forName("kotlin.coroutines.Continuation")
    val function2 = Class.forName("kotlin.jvm.functions.Function2")
    clazz.getDeclaredMethod("later", function2, continuation)

    return "OK"
}
