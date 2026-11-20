package io.github.expo.modules.v2.boxsupport

import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.modules.ModuleBuilder

/*
 * Helpers for the compiler plugin's box tests, on their compile and run classpath beside :api.
 *
 * A box test cannot reach JavaScript, so what a generated `define$ExpoModulesV2` declares is checked
 * by running it into a fresh [ModuleBuilder] and comparing the builder's rendering - one export per
 * line - with what the test expects. That is the part of the generated code an IR dump used to be
 * the only witness of.
 */

/**
 * Null when [module] declares exactly [expected] (trimmed of its indent), else a `box()` failure that
 * prints what it did declare.
 *
 * Every name the plugin generates ends in `$ExpoModulesV2`, which a Kotlin string would read as a
 * template, so the rendering drops that suffix: `greet__trampoline` stands for
 * `greet__trampoline$ExpoModulesV2`.
 */
fun expectExports(module: Module, expected: String): String? =
  compare(module.javaClass.simpleName, exportsOf(module), expected)

/** The same for a shared-object class, whose `define$ExpoModulesV2` is static. */
fun expectExports(sharedObject: Class<out SharedObject>, expected: String): String? =
  compare(sharedObject.simpleName, exportsOf(sharedObject), expected)

fun exportsOf(module: Module): String =
  ModuleBuilder().also { module.`define$ExpoModulesV2`(it) }.toString()

fun exportsOf(sharedObject: Class<out SharedObject>): String {
  val builder = ModuleBuilder()
  sharedObject.getDeclaredMethod("define\$ExpoModulesV2", ModuleBuilder::class.java)
    .apply { isAccessible = true }
    .invoke(null, builder)
  return builder.toString()
}

private fun compare(owner: String, rendered: String, expected: String): String? {
  val actual = rendered.replace(GENERATED_SUFFIX, "")
  val want = expected.trimIndent()
  return if (actual == want) {
    null
  } else {
    "$owner exports differ.\n--- expected:\n$want\n--- actual:\n$actual"
  }
}

private const val GENERATED_SUFFIX = "\$ExpoModulesV2"
