package io.github.expo.modules.v2.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Configuration entry point for the Expo Modules v2 Gradle plugin: `expoModulesV2 { ... }`. */
open class ExpoModulesV2GradleExtension @Inject constructor(objectFactory: ObjectFactory) {
  /** TypeScript type generation for the module's JavaScript surface. */
  val typegen: ExpoModulesV2TypegenOptions =
    objectFactory.newInstance(ExpoModulesV2TypegenOptions::class.java).apply {
      enabled.convention(false)
    }

  fun typegen(action: Action<ExpoModulesV2TypegenOptions>) = action.execute(typegen)
}

/**
 * ```kotlin
 * expoModulesV2 {
 *   typegen {
 *     enabled = true
 *   }
 * }
 * ```
 *
 * With typegen on, each of the project's non-test Kotlin compilations writes what it exports to
 * JavaScript to `build/generated/expo-modules-v2/typegen/<compile task>/exports.json`, in the format
 * of `ExpoModulesMacros scan-exports`. The `expoModulesTypegen` task builds one of them and writes a
 * scanner next to them, which `expo-modules generate-types` from `expo-modules-cli` takes in place of
 * the Swift one:
 *
 * ```sh
 * ./gradlew :my-module:expoModulesTypegen
 * npx expo-modules generate-types --scanner <android build dir>/generated/expo-modules-v2/typegen/scanner
 * ```
 */
abstract class ExpoModulesV2TypegenOptions {
  /** Whether the compiler plugin writes the surface. Off by default. */
  abstract val enabled: Property<Boolean>

  /**
   * The compilation the `expoModulesTypegen` task builds and its scanner prints, by compilation or
   * compile task name. By default the one a developer builds anyway: `debug` (or a flavor's
   * `freeDebug`), then `main`, then `release`.
   */
  abstract val compilation: Property<String>
}
