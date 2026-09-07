// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

// An `object` is the ordinary shape of a module, so it is deliberately absent from this file.

<!EXPO_MODULE_ON_UNSUPPORTED_DECLARATION!>@ExpoModule
interface NotAClass<!>

@ExpoModule
<!EXPO_MODULE_ON_UNSUPPORTED_DECLARATION!>enum class NotAnEnum<!> { A }

@ExpoModule
annotation <!EXPO_MODULE_ON_UNSUPPORTED_DECLARATION!>class NotAnAnnotation<!>

@ExpoModule
abstract <!EXPO_MODULE_ON_UNSUPPORTED_DECLARATION!>class NotAbstract<!> : Module()

@ExpoModule
sealed <!EXPO_MODULE_ON_UNSUPPORTED_DECLARATION!>class NotSealed<!> : Module()

@ExpoModule
<!EXPO_MODULE_WITH_TYPE_PARAMETERS!>class Generic<!><T> : Module()

class Outer {
    @ExpoModule
    inner <!EXPO_MODULE_ON_UNSUPPORTED_DECLARATION!>class NotInner<!> : Module()
}

fun containingFunction() {
    @ExpoModule
    <!EXPO_MODULE_ON_UNSUPPORTED_DECLARATION!>class NotLocal<!> : Module()
}

// Only a Module carries a module's exports; the bridge invokes its methods on one.
@ExpoModule
<!EXPO_MODULE_IS_NOT_A_MODULE!>class NotAModule<!> {
    @JS
    fun f(): Int = 1
}

// A shared object declares the same shapes, but under its own annotation.
@ExpoModule
<!EXPO_MODULE_IS_A_SHARED_OBJECT!>class WrongAnnotation<!> : io.github.expo.modules.v2.SharedObject() {
    @JS
    fun f(): Int = 1
}

// Without @ExpoModule on the class there is nothing to generate a registration into.
class ForgotTheClassAnnotation : Module() {
    @JS
    fun <!JS_MEMBER_OUTSIDE_MODULE!>f<!>(): Int = 1

    @JS
    val <!JS_MEMBER_OUTSIDE_MODULE!>p<!>: Int = 1
}

// @JS on a top-level declaration has no module to belong to.
@JS
fun <!JS_MEMBER_OUTSIDE_MODULE!>topLevel<!>(): Int = 1

@JS
val <!JS_MEMBER_OUTSIDE_MODULE!>topLevelProperty<!>: Int = 1

// `internal` is mangled on the JVM, so the bridge could never resolve it.
@ExpoModule
class InternalMembers : Module() {
    @JS
    internal fun <!JS_MEMBER_IS_INTERNAL!>hidden<!>(): Int = 1

    @JS
    internal val <!JS_MEMBER_IS_INTERNAL!>hiddenProperty<!>: Int = 1
}

// ModuleBuilder rejects a repeated export name; both declarations are named here instead.
@ExpoModule
class DuplicateNames : Module() {
    @JS
    fun value(): Int = 1

    @JS(name = "value")
    fun <!JS_DUPLICATE_EXPORT_NAME!>renamedOntoValue<!>(): Int = 2

    @JS(name = "value")
    val <!JS_DUPLICATE_EXPORT_NAME!>alsoValue<!>: Int = 3
}
