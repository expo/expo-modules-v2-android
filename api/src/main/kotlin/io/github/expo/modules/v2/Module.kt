package io.github.expo.modules.v2

import io.github.expo.modules.v2.modules.ModuleBuilder

abstract class Module : ExpoObject() {
  open fun `define$ExpoModulesV2`(builder: ModuleBuilder): String? = null

  /**
   * The name [define$ExpoModulesV2] returns, without defining anything. The compiler plugin
   * generates it, so the registry can register a module by name and put its definition off until
   * JavaScript first reads it. Null for a module from an older plugin, which is defined at once.
   */
  open fun `name$ExpoModulesV2`(): String? = null

  open fun onDestroy() = Unit
}
