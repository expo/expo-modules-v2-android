package io.github.expo.modules.v2.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import javax.inject.Inject

/** Configuration entry point for the Expo Modules v2 Gradle plugin, the `expoModulesV2` block. */
open class ExpoModulesV2GradleExtension @Inject constructor(objectFactory: ObjectFactory) {
  /**
   * The package of the modules in each library the project depends on, keyed by the library's
   * component - the same thing a dependency declaration names, not a file:
   *  - a project dependency by its path in the build tree, such as `:expo-font`;
   *  - a module dependency by `group:name`, without its version, such as `host.exp.exponent:expo-font`.
   *
   * For example, an app maps each module library to its npm package, such as `expo-font`.
   * `discoveredExpoPackages()` groups the modules by it. A module of a component that is not here is
   * only in `discoveredExpoModules()`.
   *
   * Each Kotlin compilation resolves its own compile classpath and passes the compiler the files of
   * these components on it, so one map covers every build variant. A compilation with none of these
   * components gets an empty map; one where some classpath modules have a package and others do not
   * warns about the latter. A package whose files hold no module is not in the map.
   */
  val componentPackages: MapProperty<String, String> =
    objectFactory.mapProperty(String::class.java, String::class.java).convention(emptyMap())
}
