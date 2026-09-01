// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.annotations.JS
import io.github.expo.modules.v2.modules.Module

// An `object` is the ordinary shape of a module, so it is deliberately absent from this file.

<!JS_ON_UNSUPPORTED_DECLARATION!>@JS
interface NotAClass<!>

@JS
<!JS_ON_UNSUPPORTED_DECLARATION!>enum class NotAnEnum<!> { A }

@JS
annotation <!JS_ON_UNSUPPORTED_DECLARATION!>class NotAnAnnotation<!>

@JS
abstract <!JS_ON_UNSUPPORTED_DECLARATION!>class NotAbstract<!> : Module()

@JS
sealed <!JS_ON_UNSUPPORTED_DECLARATION!>class NotSealed<!> : Module()

@JS
<!JS_CLASS_WITH_TYPE_PARAMETERS!>class Generic<!><T> : Module()

class Outer {
    @JS
    inner <!JS_ON_UNSUPPORTED_DECLARATION!>class NotInner<!> : Module()
}

fun containingFunction() {
    @JS
    <!JS_ON_UNSUPPORTED_DECLARATION!>class NotLocal<!> : Module()
}

// The registry registers a Module, and the bridge invokes methods on one.
@JS
<!JS_CLASS_IS_NOT_A_MODULE!>class NotAModule<!> {
    @JS
    fun f(): Int = 1
}

// Without @JS on the class there is nothing to generate a registration into.
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
@JS
class InternalMembers : Module() {
    @JS
    internal fun <!JS_MEMBER_IS_INTERNAL!>hidden<!>(): Int = 1

    @JS
    internal val <!JS_MEMBER_IS_INTERNAL!>hiddenProperty<!>: Int = 1
}

// ModuleBuilder rejects a repeated export name; both declarations are named here instead.
@JS
class DuplicateNames : Module() {
    @JS
    fun value(): Int = 1

    @JS(name = "value")
    fun <!JS_DUPLICATE_EXPORT_NAME!>renamedOntoValue<!>(): Int = 2

    @JS(name = "value")
    val <!JS_DUPLICATE_EXPORT_NAME!>alsoValue<!>: Int = 3
}
