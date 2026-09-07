// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

// A plain `val` on an `@ExpoModule` class is the ordinary shape of an exported property, so the
// accepted shapes are collected at the bottom of this file.

// The container is the companion object, and the companion carries no `@ExpoModule`.
@ExpoModule
class CompanionMembers : Module() {
    companion object {
        @JS
        val <!JS_MEMBER_OUTSIDE_MODULE!>fromCompanion<!>: Int = 1
    }
}

// A nested class is its own container, and it carries no `@ExpoModule` either.
@ExpoModule
class NestedContainer : Module() {
    class Inner {
        @JS
        val <!JS_MEMBER_OUTSIDE_MODULE!>fromNested<!>: Int = 1
    }
}

// A local variable never reaches the checker: `@JS` does not target one.
fun localVariable() {
    <!WRONG_ANNOTATION_TARGET!>@JS<!>
    val local: Int = 1
}

// The bridge reads a property off the module instance, so there is no receiver to bind.
@ExpoModule
class ExtensionProperties : Module() {
    @JS
    val Int.<!JS_UNSUPPORTED_FUNCTION_SHAPE!>received<!>: Int get() = this

    // Visibility is checked before the receiver, so only the visibility is reported here.
    @JS
    internal val Int.<!JS_MEMBER_IS_INTERNAL!>internalAndReceived<!>: Int get() = this
}

// Accepted shapes: none of these report.
@ExpoModule
class GoodProperties : Module() {
    @JS
    val readOnly: Int = 1

    @JS
    var mutable: Int = 1

    @JS
    val computed: Int get() = 1

    @JS(name = "renamed")
    val original: Int = 1

    // Only `internal` is mangled on the JVM, so `private` is left alone.
    @JS
    private val hidden: Int = 1

    // Not exported, so the checker never looks at the receiver.
    val Int.notExported: Int get() = this
}

@ExpoModule
object GoodObjectProperties : Module() {
    @JS
    val fromObject: Int = 1
}
