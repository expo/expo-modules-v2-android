package io.github.expo.modules.v2.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Configuration entry point for the Expo Modules v2 Gradle plugin, the `expoModulesV2` block. */
open class ExpoModulesV2GradleExtension @Inject constructor(objectFactory: ObjectFactory) {
  /**
   * The package of the modules this project declares, such as the npm package `expo-font`. The
   * compiler plugin records it on their module hints, so `discoveredExpoPackages()` in an app that
   * depends on this library - from source or as a published artifact - groups them under it, with
   * nothing to configure in the app.
   *
   * By default, the `name` of the `package.json` one directory up, which is where an npm package
   * keeps its Android library (`node_modules/<package>/android`). Unset when there is none: the
   * modules are then only in `discoveredExpoModules()`.
   */
  val packageName: Property<String> = objectFactory.property(String::class.java)
}
