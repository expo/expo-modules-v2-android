// FIR_IDENTICAL

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Constant
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.boxsupport.assertExports
import io.github.expo.modules.v2.boxsupport.buffered
import io.github.expo.modules.v2.boxsupport.slot
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
    assertExports(Info) {
        property("applicationId", slot<String>()) { constant() }
        property("uptime", slot<Int>())
        property("version", slot<Int>()) { constant() }
        property("names", buffered<List<String>>()) { trampoline() }
        sharedClass("Item", Item::class)
    }
    assertExports(Item::class) {
        property("name", slot<String>()) { constant() }
        property("count", slot<Int>()) { constant() }
        property("tags", buffered<List<String>>()) { trampoline() }
    }

    val name = Info.`define$ExpoModulesV2`(ModuleBuilder())
    if (name != "Info") return "unexpected module name: $name"
    // The registry registers the module by this name and defines it only when JavaScript reads it.
    val reported = Info.`name$ExpoModulesV2`()
    return if (reported == name) "OK" else "name\$ExpoModulesV2 reported $reported, define named $name"
}
