package io.github.expo.modules.v2.react

import io.github.expo.modules.v2.core.ExpoModulesV2

/**
 * Loads the Android bridge library.
 *
 * The desktop build splits the stack in two — `libexpo-kolibri` (bridge) and `libexpo-hermes`
 * (engine) — because it has to create a VM. On Android there is no engine to ship: React Native's
 * process already holds the runtime and `libjsi.so`, so everything the port needs is in the single
 * `libexpo-kolibri.so` this module packages, which is exactly the library
 * [ExpoModulesV2.load] asks for.
 */
object ExpoModulesV2React {
  fun ensureLoaded() = ExpoModulesV2.load()
}
