// FIR_IDENTICAL

// MODULE: font
// EXPO_PACKAGE_NAME: expo-font
// FILE: font.kt
package io.github.expo.modules.v2.testdata.font

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.Module

/**
 * The hints of these modules hold no package. The discovering compilation gets the package of this
 * module's output in a `classpathPackage` option, and groups the modules found there under it.
 */
@ExpoModule
object FontUtils : Module()

@ExpoModule
object FontLoader : Module()

// MODULE: ui
// EXPO_PACKAGE_NAME: @expo/ui
// FILE: ui.kt
package io.github.expo.modules.v2.testdata.ui

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.Module

/** A scoped npm package name stays as it is. */
@ExpoModule
object Ui : Module()

// MODULE: unnamed
// FILE: unnamed.kt
package io.github.expo.modules.v2.testdata.unnamed

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.Module

/** An output that the discovering compilation has no package for, so its modules have none. */
@ExpoModule
object Unnamed : Module()

// MODULE: app(font, ui, unnamed)
// FILE: app.kt
package io.github.expo.modules.v2.testdata.app

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.discoveredExpoModules
import io.github.expo.modules.v2.discoveredExpoPackages
import io.github.expo.modules.v2.testdata.font.FontLoader
import io.github.expo.modules.v2.testdata.font.FontUtils
import io.github.expo.modules.v2.testdata.ui.Ui
import io.github.expo.modules.v2.testdata.unnamed.Unnamed

/** A module of the discovering compilation itself is in no package. */
@ExpoModule
object Local : Module()

private fun packages(): Map<String, List<Class<out Module>>> = discoveredExpoPackages()

private fun modules(): List<Class<out Module>> = discoveredExpoModules()

fun box(): String {
    val packages = packages()

    // Packages sorted by name, and the modules of each package by class name.
    val expected = listOf<Pair<String, List<Class<out Module>>>>(
        "@expo/ui" to listOf(Ui::class.java),
        "expo-font" to listOf(FontLoader::class.java, FontUtils::class.java),
    )
    val actual = packages.toList()
    if (actual != expected) {
        return "packages: ${actual.map { (name, modules) -> name to modules.map { it.simpleName } }}"
    }

    // A module without a package is still discovered, only not in any package.
    val modules = modules()
    for (module in listOf(Unnamed::class.java, Local::class.java)) {
        if (module !in modules) return "missing ${module.simpleName} in ${modules.map { it.simpleName }}"
    }
    if (packages.values.flatten().size != modules.size - 2) return "modules out of packages: $modules"

    return "OK"
}
