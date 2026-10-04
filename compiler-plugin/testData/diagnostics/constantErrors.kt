// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Constant
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject

// JavaScript reads a constant once and keeps the value, so it needs a @JS getter and nothing to
// write it with.

@ExpoModule
class ConstantShapes : Module() {
    @JS
    @Constant
    val fine: Int = 1

    @Constant
    val <!CONSTANT_ON_NON_EXPORTED_PROPERTY!>notExported<!>: Int = 1

    @JS
    @Constant
    var <!CONSTANT_ON_MUTABLE_PROPERTY!>mutable<!>: Int = 1
}

// A shared object keeps a constant on each instance.
@ExpoSharedObject
class SharedConstants : SharedObject() {
    @JS
    @Constant
    val perInstance: Int = 1

    @JS
    @Constant
    var <!CONSTANT_ON_MUTABLE_PROPERTY!>mutable<!>: Int = 1
}

/* GENERATED_FIR_TAGS: classDeclaration, integerLiteral, propertyDeclaration */
