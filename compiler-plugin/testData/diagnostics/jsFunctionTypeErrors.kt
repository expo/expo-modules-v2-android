// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.Union
import io.github.expo.modules.v2.async.Promise

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

    // A lambda may return a Promise, to get the JavaScript one without waiting for it.
    @JS
    fun promised(callback: (Int) -> Promise<String>, other: suspend () -> Promise<Unit>) = callback(1)

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>takesPromise<!>(callback: (Promise<Int>) -> Unit) = Unit

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>nullablePromise<!>(callback: () -> Promise<Int>?) = Unit

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>starPromise<!>(callback: () -> Promise<*>) = Unit

    // Eight parameters is the limit, as for a function.
    @JS
    fun eight(callback: (Int, Int, Int, Int, Int, Int, Int, Int) -> Unit) = callback(1, 2, 3, 4, 5, 6, 7, 8)

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>nine<!>(callback: (Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit) =
        callback(1, 2, 3, 4, 5, 6, 7, 8, 9)

    // A JavaScript function only crosses on its own: never inside a list, a map or a lambda's
    // own arguments, and never as a property.
    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>listOfLambdas<!>(callbacks: List<(Int) -> Unit>) = callbacks.forEach { it(1) }

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>returnsListOfLambdas<!>(): List<() -> Int> = listOf({ 1 })

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>lambdaTakingListOfLambdas<!>(callback: (List<() -> Int>) -> Unit) = callback(emptyList())

    @JS
    val <!JS_PROPERTY_WITH_FUNCTION_TYPE!>lambdaProperty<!>: () -> Int = { 1 }

    @Event
    val <!EVENT_UNSUPPORTED_SHAPE!>onCallback<!> = event<(Int) -> Unit>()

    @Event
    val <!EVENT_UNSUPPORTED_SHAPE!>onCallbacks<!> = event<List<() -> Unit>>()
}

@Record
class WithLambdaField(<!RECORD_FIELD_WITH_FUNCTION_TYPE!>val callback: () -> Int<!>)

@Union
sealed interface WithLambdaVariant {
    <!UNION_UNSUPPORTED_VARIANT!>class Callback<!>(val value: () -> Int) : WithLambdaVariant

    class Text(val value: String) : WithLambdaVariant
}

// A @JS constructor follows the rules of a @JS function.
@ExpoSharedObject
class MarkedConstructor <!JS_UNSUPPORTED_FUNCTION_SHAPE!>@JS constructor(callbacks: List<() -> Unit>)<!> : SharedObject()

@ExpoSharedObject
class MarkedReceiverConstructor <!JS_UNSUPPORTED_FUNCTION_SHAPE!>@JS constructor(configure: MarkedReceiverConstructor.() -> Unit)<!> : SharedObject()

// An unmarked constructor that takes a lambda is never exposed, so it is free to take any.
@ExpoSharedObject
class OnlyConstructor(callbacks: Map<String, () -> Unit>) : SharedObject()

@ExpoSharedObject
class ReceiverConstructor(configure: ReceiverConstructor.() -> Unit) : SharedObject()

@ExpoSharedObject
class LambdaConstructor(callback: (Int) -> Unit) : SharedObject()
