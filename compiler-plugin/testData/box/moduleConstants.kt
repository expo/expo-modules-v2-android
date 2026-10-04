// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Constant
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.modules.ModuleBuilder

/**
 * A constant tells the bridge so in the definition, which passes `constant = true` for it; a plain
 * property keeps the default. A `val` that only holds a fixed value is a constant without the
 * annotation, and one whose contents Kotlin can still change is not.
 */
/** A shared object declares constants the same way, and keeps them on each instance. */
@ExpoSharedObject
class Item : SharedObject() {
    @JS
    @Constant
    val name: String
        get() = "item"

    @JS
    val count = 1

    @JS
    val tags = mutableListOf("a")
}

@ExpoModule(classes = [Item::class])
object Info : Module() {
    @JS
    @Constant
    val applicationId: String
        get() = "dev.expo.payments"

    @JS
    val uptime: Int
        get() = 1

    @JS
    val version = 3

    @JS
    val names = mutableListOf("a")
}

fun box(): String {
    val name = Info.`define$ExpoModulesV2`(ModuleBuilder())
    return if (name == "Info") "OK" else "unexpected module name: $name"
}
