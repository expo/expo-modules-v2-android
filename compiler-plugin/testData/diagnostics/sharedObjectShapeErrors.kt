// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject

// A shared object is an instance JavaScript holds a reference to, so these shapes cannot be one.

<!SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION!>@ExpoSharedObject
interface NotAClass<!>

@ExpoSharedObject
<!SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION!>enum class NotAnEnum<!> { A }

@ExpoSharedObject
annotation <!SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION!>class NotAnAnnotation<!>

@ExpoSharedObject
abstract <!SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION!>class NotAbstract<!>

@ExpoSharedObject
sealed <!SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION!>class NotSealed<!>

// A shared object is handed out per reference, so there is nothing for a singleton to be.
@ExpoSharedObject
<!SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION!>object NotAnObject<!>

@ExpoSharedObject
<!SHARED_OBJECT_WITH_TYPE_PARAMETERS!>class Generic<!><T>

class Outer {
    @ExpoSharedObject
    inner <!SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION!>class NotInner<!>
}

fun containingFunction() {
    @ExpoSharedObject
    <!SHARED_OBJECT_ON_UNSUPPORTED_DECLARATION!>class NotLocal<!>
}

// One class is one base: a module is registered under its own name, never held as a reference.
@ExpoModule
@ExpoSharedObject
<!SHARED_OBJECT_WITH_EXPO_MODULE_ANNOTATION!>class BothAnnotations<!>

@ExpoSharedObject
<!SHARED_OBJECT_EXTENDS_MODULE!>class AModule<!> : Module()

open class SomeoneElsesBase

// The JVM allows one superclass, and SharedObject has to be it.
@ExpoSharedObject
<!SHARED_OBJECT_IS_NOT_A_SHARED_OBJECT!>class HasItsOwnBase<!> : SomeoneElsesBase()

// The likely slip: the annotation on its own, with the base class forgotten.
@ExpoSharedObject
<!SHARED_OBJECT_IS_NOT_A_SHARED_OBJECT!>class ForgotTheBase<!>

// `new` reaches one constructor, and only on a shared object.
@ExpoModule
class NotConstructable : Module {
    <!SHARED_OBJECT_CONSTRUCTOR_OUTSIDE_SHARED_OBJECT!>@JS
    constructor(n: Int) : super()<!>
}

// Two marked constructors: `new` reaches one, so the second is ambiguous.
@ExpoSharedObject
class TwoConstructors @JS constructor(n: Int) : SharedObject() {
    <!SHARED_OBJECT_DUPLICATE_CONSTRUCTOR!>@JS
    constructor(text: String) : this(text.length)<!>
}

// Several constructors and no mark: nothing is exposed, which is not an error.
@ExpoSharedObject
class Ambiguous(n: Int) : SharedObject() {
    constructor(text: String) : this(text.length)
}

// A sole constructor that is not public is the way to opt out of `new`.
@ExpoSharedObject
class NotPublic private constructor(n: Int) : SharedObject()

// A member is only exported when its class carries one of the two annotations.
class ForgotTheClassAnnotation {
    @JS
    fun <!JS_MEMBER_OUTSIDE_MODULE!>f<!>(): Int = 1
}

// ModuleBuilder rejects a repeated export name on a shared object just as it does on a module.
@ExpoSharedObject
class DuplicateNames : SharedObject() {
    @JS
    fun value(): Int = 1

    @JS(name = "value")
    fun <!JS_DUPLICATE_EXPORT_NAME!>renamedOntoValue<!>(): Int = 2
}
