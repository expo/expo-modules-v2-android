package io.github.expo.modules.v2.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Configuration entry point for the Expo Modules v2 Gradle plugin, the `expoModulesV2` block. */
open class ExpoModulesV2GradleExtension @Inject constructor(objectFactory: ObjectFactory) {
  /**
   * The package of the modules this project declares, such as the npm package `expo-font`. Passed to
   * the compiler plugin as its `packageName` option, which records it on the modules' hints, so
   * `discoveredExpoPackages()` in an app that depends on this library - from source or as a
   * published artifact - groups them under it, with nothing to configure in the app.
   *
   * Nothing sets it by default. The build that knows the package sets it: in an Expo app,
   * autolinking, from the package each module library is linked for. Unset, the modules are only in
   * `discoveredExpoModules()`.
   */
  val packageName: Property<String> = objectFactory.property(String::class.java)
}
