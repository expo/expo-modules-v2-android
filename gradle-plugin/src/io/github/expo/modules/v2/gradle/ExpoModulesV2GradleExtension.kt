package io.github.expo.modules.v2.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import javax.inject.Inject

/** Configuration entry point for the Expo Modules v2 Gradle plugin, the `expoModulesV2` block. */
open class ExpoModulesV2GradleExtension @Inject constructor(objectFactory: ObjectFactory) {
  /**
   * The package of the modules in each compile classpath entry, keyed by the entry's absolute path:
   * a jar or a classes directory that the project's Kotlin compilation gets. For example, an app
   * maps the classes jar of each module library to its npm package, such as `expo-font`.
   * `discoveredExpoPackages()` groups the modules by it. A module in an entry that is not here is
   * only in `discoveredExpoModules()`.
   */
  val classpathPackages: MapProperty<String, String> =
    objectFactory.mapProperty(String::class.java, String::class.java).convention(emptyMap())
}
