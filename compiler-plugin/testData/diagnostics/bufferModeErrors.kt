// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject

// A transport choice only means something where a value actually crosses the bridge.

@BufferMode(Buffer.NO)
<!BUFFER_MODE_ON_NON_EXPORTED_DECLARATION!>class NotExported<!> : Module()

@ExpoModule
class UnexportedMembers : Module() {
    @BufferMode(Buffer.NO)
    fun <!BUFFER_MODE_ON_NON_EXPORTED_DECLARATION!>plain<!>(): Int = 1

    @BufferMode(Buffer.NO)
    val <!BUFFER_MODE_ON_NON_EXPORTED_DECLARATION!>plainProperty<!>: Int = 1

    fun plainArgument(@BufferMode(Buffer.NO) <!BUFFER_MODE_ON_NON_EXPORTED_DECLARATION!>value<!>: String): Int = 1
}

// A shared object's constructor arguments cross only when JavaScript's `new` reaches them.
@ExpoSharedObject
class NotConstructable(@BufferMode(Buffer.NO) text: String) : SharedObject()

// `returns` narrows a member's result, and neither a class nor an argument has one.
@ExpoModule
@BufferMode(Buffer.NO, returns = Buffer.YES)
<!BUFFER_MODE_RETURNS_WITHOUT_RESULT!>class ClassReturns<!> : Module()

@ExpoModule
class ArgumentReturns : Module() {
    @JS
    fun f(@BufferMode(Buffer.NO, returns = Buffer.YES) <!BUFFER_MODE_RETURNS_WITHOUT_RESULT!>value<!>: String): Int = 1
}

// The accepted shapes, so the checker is shown to leave them alone.
@ExpoModule
@BufferMode(Buffer.NO)
class Accepted : Module() {
    @JS
    @BufferMode(Buffer.YES, returns = Buffer.NO)
    fun f(@BufferMode(Buffer.YES) value: String): Int = 1

    @JS
    @BufferMode(Buffer.NO)
    val p: Int = 1
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, primaryConstructor, propertyDeclaration */
