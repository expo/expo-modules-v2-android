// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

@ExpoModule
class BadFunctionTypes : Module() {
    // Accepted: a lambda parameter is a JavaScript function, plain or suspending, nullable or not.
    @JS
    fun plain(callback: (Int, String) -> Unit) = callback(1, "")

    @JS
    suspend fun suspending(callback: suspend (Int) -> String?, other: ((Int) -> Unit)?) = callback(1)

    // JavaScript passes its arguments by position, so there is nowhere for a receiver to go.
    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>withReceiver<!>(callback: String.() -> Unit) {
        "".callback()
    }

    // A lambda cannot be handed to JavaScript.
    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>returnsLambda<!>(): (Int) -> Int = { it }

    // The bridge converts what a lambda takes and returns on its own, which a lambda cannot be.
    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>takesLambdaOfLambda<!>(callback: ((Int) -> Unit) -> Unit) {
        callback { }
    }

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>returnsLambdaFromLambda<!>(callback: () -> (Int) -> Unit) {
        callback()
    }

    // Eight parameters is the limit, as for a function.
    @JS
    fun eight(callback: (Int, Int, Int, Int, Int, Int, Int, Int) -> Unit) = callback(1, 2, 3, 4, 5, 6, 7, 8)

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>nine<!>(callback: (Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit) =
        callback(1, 2, 3, 4, 5, 6, 7, 8, 9)
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, integerLiteral, lambdaLiteral,
nullableType, stringLiteral, suspend, typeWithExtension */
