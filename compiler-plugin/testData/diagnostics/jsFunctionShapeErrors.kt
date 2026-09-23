// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.Module

@ExpoModule
class BadFunctions : Module() {
    @JS
    fun <T> <!JS_UNSUPPORTED_FUNCTION_SHAPE!>generic<!>(value: Int): Int = value

    @JS
    fun Int.<!JS_UNSUPPORTED_FUNCTION_SHAPE!>extension<!>(): Int = this

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>withVararg<!>(vararg values: Int): Int = values.size

    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>withDefault<!>(a: Int, b: Int = 2): Int = a + b

    // The bridge packs at most Trampoline.MAX_ARGUMENTS arguments.
    @JS
    fun <!JS_UNSUPPORTED_FUNCTION_SHAPE!>tooManyArguments<!>(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        e: Int,
        f: Int,
        g: Int,
        h: Int,
        i: Int,
    ): Int = a + b + c + d + e + f + g + h + i

    @JS
    val Int.<!JS_UNSUPPORTED_FUNCTION_SHAPE!>extensionProperty<!>: Int get() = this
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, funWithExtensionReceiver, functionDeclaration, getter,
integerLiteral, nullableType, propertyDeclaration, propertyWithExtensionReceiver, thisExpression, typeParameter, vararg */
