package io.github.expo.modules.v2

import io.github.expo.modules.v2.modules.ModuleBuilder

abstract class Module : ExpoObject() {
  open fun `define$ExpoModulesV2`(builder: ModuleBuilder): String? = null
}
