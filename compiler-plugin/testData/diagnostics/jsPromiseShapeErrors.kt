// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.async.Promise

@ExpoModule
class BadPromises : Module() {
    // A promise is a result. The v1 shape, taking one to settle, is rejected with a pointer.
    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>takesPromise<!>(value: Int, promise: Promise<Int>) {
        promise.resolve(value)
    }

    // Either suspend or return a promise, never both.
    @JS
    suspend fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>suspendsAndPromises<!>(): Promise<Int> = Promise()

    // JavaScript always gets a promise, so there is nothing for null to mean.
    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>nullablePromise<!>(): Promise<Int>? = null

    // The bridge converts the result with T's descriptor, so T has to be named.
    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>starPromise<!>(): Promise<*> = Promise<Int>()

    // Accepted: a promise that may resolve with null.
    @JS
    fun nullableValue(): Promise<Int?> = Promise()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, nullableType, starProjection, suspend */
