package io.github.expo.modules.v2

import io.github.expo.modules.v2.modules.ModuleBuilder

abstract class Module : ExpoObject() {
  // The compiler plugin generates the override under exactly this name.
  @Suppress("ktlint:standard:function-naming")
  open fun `define$ExpoModulesV2`(builder: ModuleBuilder): String? = null

  open fun onDestroy() = Unit
}
