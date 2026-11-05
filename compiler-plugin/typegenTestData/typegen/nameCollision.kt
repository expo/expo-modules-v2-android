// FILE: first.kt
package io.github.expo.modules.v2.testdata.first

import io.github.expo.modules.v2.Record

@Record
data class Options(val quality: Int)

// FILE: second.kt
package io.github.expo.modules.v2.testdata.second

import io.github.expo.modules.v2.Record

/** The same name as the other `Options`: TypeScript has one namespace, so this one is left out. */
@Record
data class Options(val label: String)

// FILE: module.kt
package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module

@ExpoModule
class Settings : Module() {
    @JS
    fun first(options: io.github.expo.modules.v2.testdata.first.Options): Int = 0

    @JS
    fun second(options: io.github.expo.modules.v2.testdata.second.Options): Int = 0
}

fun box(): String = "OK"
